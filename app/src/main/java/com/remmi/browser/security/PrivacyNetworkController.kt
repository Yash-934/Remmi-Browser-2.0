package com.remmi.browser.security

import android.content.Context
import android.util.Log
import com.remmi.browser.engine.GeckoEngineManager
import com.remmi.browser.engine.TabManager
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import java.util.Scanner

/**
 * Authoritative Privacy & Network Security Controller for Remmi Browser.
 * Owns Ghost Mode / Shield Mode lifecycle, Tor state synchronization,
 * native Gecko proxy enforcement, leak prevention, and fail-closed guarantees.
 */
class PrivacyNetworkController private constructor(private val context: Context) {
  private val torManager = TorManager.getInstance(context)
  private val geckoEngine = GeckoEngineManager.getInstance(context)
  private val transitionMutex = kotlinx.coroutines.sync.Mutex()

  val torState: StateFlow<TorManager.TorState> = torManager.bootstrapState
  val currentCircuit: StateFlow<TorCircuit?> = torManager.currentCircuit

  init {
    CoroutineScope(Dispatchers.Default).launch {
      torManager.bootstrapState.collect { state ->
        if (state is TorManager.TorState.OFF || state is TorManager.TorState.FAILED || state is TorManager.TorState.STOPPING) {
          if (CurrentTorRoute.isGhostActive) {
            Log.w(TAG, "Tor stopped unexpectedly while Ghost active. Invalidating route!")
            CurrentTorRoute.clearRoute()
            NetworkHardening.resetAppliedState()
          }
        }
      }
    }
  }

  fun isTorLockedOut(): Boolean = torManager.isLockedOut()

  fun resetTorFailures() {
    torManager.resetFailures()
    DebugLogManager.log("[ROUTE] Tor failure state manually reset by user.")
  }

  /**
   * Enters Ghost Mode transactionally with Fail-Closed guarantee:
   * 1. Closes existing session to prevent clearnet leakage.
   * 2. Starts native TorService, dynamically discovers active SOCKS port, and verifies SOCKS5 handshake & check.torproject.org exit routing.
   * 3. Configures GeckoView native engine-level proxy, remote DNS, and WebRTC blocking.
   * 4. Updates CurrentTorRoute single source of truth.
   * 5. Returns success only if Tor exit routing and native Gecko proxy are verified.
   */
  suspend fun enterGhostMode(tabId: String): Result<Int> = transitionMutex.withLock {
    withContext(Dispatchers.IO) {
      if (torManager.isLockedOut()) {
        val errorMsg = "Maximum Tor start attempts exceeded. Reset required (tap RETRY to reset)."
        Log.w(TAG, "Ghost Mode admission rejected: $errorMsg")
        DebugLogManager.log("[ROUTE] ADMISSION_REJECTED reason=locked_out tabId=$tabId")
        return@withContext Result.failure(IllegalStateException(errorMsg))
      }

      Log.i(TAG, "Entering Ghost Mode for tab $tabId (enforcing fail-closed Tor routing)...")
      val generation = CurrentTorRoute.markStartingGhost()
      DebugLogManager.log("[ROUTE] REQUESTED profile=GHOST tabId=$tabId generation=$generation")

    // Step 1: Terminate active clearnet session first
    geckoEngine.closeSessionSafely(tabId)

    // Step 2: Bootstrap & verify Tor daemon (TorManager handles discovery, handshake & exit verification)
    val torResult = torManager.startTor()
    if (torResult.isFailure) {
      val error = torResult.exceptionOrNull() ?: Exception("Tor failed to initialize")
      Log.e(TAG, "Ghost Mode transition aborted: ${error.message}")
      CurrentTorRoute.clearRoute()
      NetworkHardening.resetAppliedState()
      DebugLogManager.log("[ROUTE] FAILED profile=GHOST reason=${error.message}")
      return@withContext Result.failure(error)
    }

    val socksPort = torResult.getOrNull() ?: run {
      val discovered = torManager.discoverRuntimeSocksPort()
      if (discovered <= 0) {
        val err = IllegalStateException("SOCKS port discovery returned invalid port: $discovered")
        CurrentTorRoute.clearRoute()
        NetworkHardening.resetAppliedState()
        DebugLogManager.log("[ROUTE] FAILED profile=GHOST reason=port_unavailable")
        return@withContext Result.failure(err)
      }
      discovered
    }

    DebugLogManager.log("[ROUTE] SOCKS_VERIFIED port=$socksPort")

    // Step 3: Apply hardened Tor preferences directly to native GeckoView engine
    val proxyApplied = NetworkHardening.applyTorNetworkSettings(geckoEngine.runtime, socksPort, generation)
    if (!proxyApplied) {
      val err = IllegalStateException("Failed to apply Gecko native Tor proxy preferences")
      torManager.stopTor()
      CurrentTorRoute.clearRoute()
      NetworkHardening.resetAppliedState()
      DebugLogManager.log("[ROUTE] FAILED profile=GHOST reason=gecko_proxy_failed")
      return@withContext Result.failure(err)
    }

    // Step 4: Verify Gecko is actually using the expected route with fast 5s bound
    val geckoVerified = try {
      val executor = GeckoWebExecutor(geckoEngine.runtime!!)
      val request = WebRequest.Builder("https://check.torproject.org/api/ip").build()
      val response = executor.fetch(request).poll(5000)
      if (response != null && response.statusCode == 200) {
        val bodyStream = response.body
        if (bodyStream != null) {
          val content = Scanner(bodyStream, "UTF-8").useDelimiter("\\A").next()
          bodyStream.close()
          content.contains("\"IsTor\":true")
        } else false
      } else false
    } catch (e: Exception) {
      false
    }
    
    if (!geckoVerified) {
      val err = IllegalStateException("Gecko native Tor proxy verification failed (not routing through Tor)")
      torManager.stopTor()
      CurrentTorRoute.clearRoute()
      NetworkHardening.resetAppliedState()
      DebugLogManager.log("[ROUTE] FAILED profile=GHOST reason=gecko_verification_failed")
      return@withContext Result.failure(err)
    }

    DebugLogManager.log("[ROUTE] GECKO_ROUTE_VERIFIED")
    // Step 5: Advance route generation and update Single Source of Truth
    CurrentTorRoute.updateRoute(
      socksPort = socksPort,
      isGhostActive = true,
      isVerified = true,
      exitIp = torManager.currentCircuit.value?.verifiedExitIp,
      generation = generation
    )

    geckoEngine.applyPrivacyProfile(PrivacyProfile.GHOST, socksPort, generation)
    
    // Ensure all tabs reflect the global APP-WIDE Tor proxy routing
    TabManager.getInstance().setAllTabsProfile(PrivacyProfile.GHOST)

    DebugLogManager.log("[ROUTE] GHOST_ROUTE_READY profile=GHOST port=$socksPort exitIp=${torManager.currentCircuit.value?.verifiedExitIp ?: "Active"}")
    Result.success(socksPort)
    }
  }

  /**
   * Enters Shield Mode (Direct Clearnet with Fingerprinting Protection & Adblock):
   * 1. Closes existing Ghost session.
   * 2. Clears SOCKS proxy from WebExtension & native Gecko engine.
   * 3. Stops Tor and updates all tabs to reflect the direct clearnet routing.
   */
  suspend fun enterShieldMode(tabId: String): Unit = transitionMutex.withLock { withContext(Dispatchers.IO) {
    Log.i(TAG, "Entering Shield Mode for tab $tabId (restoring direct clearnet)...")

    geckoEngine.closeSessionSafely(tabId)

    val generation = CurrentTorRoute.clearRoute()
            NetworkHardening.resetAppliedState()
    DebugLogManager.log("[ROUTE] REQUESTED profile=SHIELD tabId=$tabId generation=$generation")
    torManager.stopTor()
    NetworkHardening.applyShieldNetworkSettings(geckoEngine.runtime, generation)
    geckoEngine.applyPrivacyProfile(PrivacyProfile.SHIELD, null, generation)
    
    // Ensure all tabs reflect the global APP-WIDE direct routing
    TabManager.getInstance().setAllTabsProfile(PrivacyProfile.SHIELD)

    DebugLogManager.log("[ROUTE] ACTIVE profile=SHIELD")
  }
  }

  /**
   * Rotates Tor circuit using genuine NEWNYM signal.
   */
  suspend fun rotateTorCircuit(): Result<TorCircuit> = transitionMutex.withLock {
    val generation = CurrentTorRoute.markStartingGhost()
    val result = torManager.refreshCircuit()
    
    if (result.isFailure) {
      CurrentTorRoute.clearRoute()
            NetworkHardening.resetAppliedState()
      return result
    }
    
    val c = result.getOrNull()
    if (c != null) {
      val proxyApplied = NetworkHardening.applyTorNetworkSettings(geckoEngine.runtime, c.socksPort, generation)
      if (!proxyApplied) {
        CurrentTorRoute.clearRoute()
            NetworkHardening.resetAppliedState()
        return Result.failure(IllegalStateException("Failed to apply Gecko Tor proxy preferences on circuit rotation"))
      }
      
      val geckoVerified = try {
        val executor = GeckoWebExecutor(geckoEngine.runtime!!)
        val request = WebRequest.Builder("https://check.torproject.org/api/ip").build()
        val response = executor.fetch(request).poll(10000)
        if (response != null && response.statusCode == 200) {
          val bodyStream = response.body
          if (bodyStream != null) {
            val content = Scanner(bodyStream, "UTF-8").useDelimiter("\\A").next()
            bodyStream.close()
            content.contains("\"IsTor\":true")
          } else false
        } else false
      } catch (e: Exception) {
        false
      }
      
      if (!geckoVerified) {
        CurrentTorRoute.clearRoute()
            NetworkHardening.resetAppliedState()
        return Result.failure(IllegalStateException("Gecko native Tor proxy verification failed on circuit rotation"))
      }
      
      CurrentTorRoute.updateRoute(
        socksPort = c.socksPort,
        isGhostActive = true,
        isVerified = true,
        exitIp = c.verifiedExitIp,
        generation = generation
      )
    }
    return result
  }

  /**
   * Performs real zero-leak routing verification against check.torproject.org.
   */
  suspend fun verifyRouting(socksPort: Int? = CurrentTorRoute.currentSocksPort): TorStatusResult {
    val port = socksPort ?: CurrentTorRoute.currentSocksPort
    if (port == null || port <= 0) {
      return TorStatusResult(
        isTor = false,
        ip = "Disconnected",
        message = "No active Tor SOCKS port configured",
        latencyMs = 0L,
        socksHandshakePassed = false
      )
    }
    return TorStatusChecker.verifyTorRouting(port)
  }

  /**
   * Checks if Ghost Mode is currently verified and ready.
   */
  fun isGhostRoutingReady(): Boolean {
    return CurrentTorRoute.isReady
    
      
      
  }

  companion object {
    private const val TAG = "PrivacyNetworkCtrl"

    @Volatile
    private var INSTANCE: PrivacyNetworkController? = null

    fun getInstance(context: Context): PrivacyNetworkController {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: PrivacyNetworkController(context.applicationContext).also { INSTANCE = it }
      }
    }
  }
}
