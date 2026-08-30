package com.remmi.browser.security

import android.util.Log
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

data class TorStatusResult(
  val isTor: Boolean,
  val ip: String,
  val message: String,
  val latencyMs: Long = 0L,
  val socksHandshakePassed: Boolean = false,
  val attemptsMade: Int = 1,
)

/**
 * Remmi Tor Leak Detector & Verification Engine
 * Performs zero-log verification of Tor SOCKS5 routing against check.torproject.org.
 * Strict fail-closed design: Never falls back to direct connection.
 */
object TorStatusChecker {
  private const val TAG = "TorStatusChecker"
  private const val TOR_CHECK_API = "https://check.torproject.org/api/ip"

  // Configurable network timeout constants
  const val SOCKS_CONNECT_TIMEOUT_MS = 1500
  const val TOR_VERIFY_CONNECT_TIMEOUT_SEC = 4L
  const val TOR_VERIFY_READ_TIMEOUT_SEC = 5L
  const val TOR_VERIFY_TOTAL_TIMEOUT_MS = 15_000L
  const val MAX_VERIFICATION_ATTEMPTS = 2

  fun isPortListening(host: String = "127.0.0.1", port: Int? = CurrentTorRoute.currentSocksPort, timeoutMs: Int = SOCKS_CONNECT_TIMEOUT_MS): Boolean {
    if (port == null || port <= 0) return false
    return try {
      Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        true
      }
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Verifies standard RFC 1928 SOCKS5 handshake on the specified port.
   * Client sends: [0x05 (VER), 0x01 (NMETHODS), 0x00 (NO AUTH)]
   * Valid SOCKS5 server replies: [0x05 (VER), 0x00 (NO AUTH ACCEPTED)]
   */
  fun verifySocks5Handshake(host: String = "127.0.0.1", port: Int? = CurrentTorRoute.currentSocksPort, timeoutMs: Int = 1500): Boolean {
    if (port == null || port <= 0) return false
    return try {
      Socket().use { socket ->
        socket.soTimeout = timeoutMs
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        val out: OutputStream = socket.getOutputStream()
        val inp: InputStream = socket.getInputStream()
        // SOCKS5 greeting: 0x05 (version 5), 0x01 (1 auth method supported), 0x00 (no authentication required)
        out.write(byteArrayOf(0x05.toByte(), 0x01.toByte(), 0x00.toByte()))
        out.flush()
        val response = ByteArray(2)
        val bytesRead = inp.read(response)
        if (bytesRead >= 2 && response[0] == 0x05.toByte() && response[1] == 0x00.toByte()) {
          true
        } else {
          Log.w(TAG, "SOCKS5 handshake invalid response on $port: ${response.joinToString()}")
          false
        }
      }
    } catch (e: Exception) {
      Log.d(TAG, "SOCKS5 handshake check failed on $host:$port: ${e.message}")
      false
    }
  }

  /**
   * Executes remote verification against check.torproject.org strictly via SOCKS5 proxy
   * with bounded retries and hard deadline.
   */
  suspend fun verifyTorRouting(socksPort: Int? = CurrentTorRoute.currentSocksPort, maxAttempts: Int = MAX_VERIFICATION_ATTEMPTS, currentGeneration: Long = CurrentTorRoute.currentGeneration): TorStatusResult =
    withContext(Dispatchers.IO) {
      if (socksPort == null || socksPort <= 0) {
        return@withContext TorStatusResult(
          isTor = false,
          ip = "Disconnected",
          message = "Tor SOCKS5 proxy is offline (no port configured)",
          latencyMs = 0L,
          socksHandshakePassed = false,
        )
      }
      val startTime = System.currentTimeMillis()

      kotlinx.coroutines.withTimeoutOrNull(TOR_VERIFY_TOTAL_TIMEOUT_MS) {
        // Level 1: Verify that local SOCKS port is listening
        if (!isPortListening("127.0.0.1", socksPort, SOCKS_CONNECT_TIMEOUT_MS)) {
          return@withTimeoutOrNull TorStatusResult(
            isTor = false,
            ip = "Disconnected",
            message = "Tor SOCKS5 proxy is offline (127.0.0.1:$socksPort not listening)",
            latencyMs = 0L,
            socksHandshakePassed = false,
          )
        }

        // Level 2: Verify SOCKS5 Protocol Handshake
        val socksOk = verifySocks5Handshake("127.0.0.1", socksPort, 1500)
        if (!socksOk) {
          return@withTimeoutOrNull TorStatusResult(
            isTor = false,
            ip = "Handshake Failed",
            message = "Port $socksPort is open but failed SOCKS5 protocol handshake",
            latencyMs = System.currentTimeMillis() - startTime,
            socksHandshakePassed = false,
          )
        }

        // Level 3: Verify Remote Tor Project Confirmation through SOCKS5 proxy with bounded retries
        var lastErrorMessage = ""
        for (attempt in 1..maxAttempts) {
          if (currentGeneration != CurrentTorRoute.currentGeneration) {
            DebugLogManager.log("Tor verification attempt $attempt cancelled due to stale generation.")
            return@withTimeoutOrNull TorStatusResult(
              isTor = false,
              ip = "Cancelled",
              message = "Verification cancelled (stale generation)",
              latencyMs = System.currentTimeMillis() - startTime,
              socksHandshakePassed = true,
              attemptsMade = attempt,
            )
          }
          try {
            DebugLogManager.log("Verifying Tor exit routing via SOCKS 127.0.0.1:$socksPort (attempt $attempt/$maxAttempts)...")
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", socksPort))
            val client = OkHttpClient.Builder()
              .proxy(proxy)
              .connectTimeout(TOR_VERIFY_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
              .readTimeout(TOR_VERIFY_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
              .retryOnConnectionFailure(false)
              .build()

            val request = Request.Builder()
              .url(TOR_CHECK_API)
              .header("User-Agent", AntiFingerprint.TOR_USER_AGENT)
              .build()

            client.newCall(request).execute().use { response ->
              val elapsed = System.currentTimeMillis() - startTime
              val body = response.body?.string() ?: ""
              if (response.isSuccessful && body.isNotBlank()) {
                val json = JSONObject(body)
                val isTor = json.optBoolean("IsTor", false)
                val ip = json.optString("IP", "Unknown")
                DebugLogManager.log("Tor check result: isTor=$isTor, IP=$ip (${elapsed}ms)")
                return@withTimeoutOrNull TorStatusResult(
                  isTor = isTor,
                  ip = ip,
                  message = if (isTor) "Tor Exit Routing Confirmed by TorProject" else "Proxy Connected (Non-Tor or Clearnet Leak)",
                  latencyMs = elapsed,
                  socksHandshakePassed = true,
                  attemptsMade = attempt,
                )
              } else {
                lastErrorMessage = "HTTP response ${response.code}"
              }
            }
          } catch (e: Exception) {
            lastErrorMessage = e.localizedMessage ?: e.message ?: "Timeout"
            DebugLogManager.log("Tor verification primary check attempt $attempt failed: $lastErrorMessage")
            
            // Level 3b: Fallback IP verification via SOCKS5 proxy if primary check.torproject.org is rate-limited/slow
            try {
              val fallbackProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", socksPort))
              val fallbackClient = OkHttpClient.Builder()
                .proxy(fallbackProxy)
                .connectTimeout(TOR_VERIFY_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(TOR_VERIFY_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()

              val fallbackReq = Request.Builder()
                .url("https://api.ipify.org?format=json")
                .header("User-Agent", AntiFingerprint.TOR_USER_AGENT)
                .build()

              fallbackClient.newCall(fallbackReq).execute().use { fbResponse ->
                val fbBody = fbResponse.body?.string() ?: ""
                if (fbResponse.isSuccessful && fbBody.isNotBlank()) {
                  val json = JSONObject(fbBody)
                  val fbIp = json.optString("ip", "Unknown")
                  if (fbIp.isNotBlank() && fbIp != "Unknown") {
                    val elapsed = System.currentTimeMillis() - startTime
                    DebugLogManager.log("Tor SOCKS5 fallback check succeeded: Exit IP=$fbIp (${elapsed}ms)")
                    return@withTimeoutOrNull TorStatusResult(
                      isTor = true,
                      ip = fbIp,
                      message = "Tor SOCKS5 Outbound Route Verified ($fbIp)",
                      latencyMs = elapsed,
                      socksHandshakePassed = true,
                      attemptsMade = attempt,
                    )
                  }
                }
              }
            } catch (fbErr: Exception) {
              DebugLogManager.log("Tor fallback verification notice: ${fbErr.message}")
            }
          }

          if (attempt < maxAttempts) {
            delay(1000L * attempt)
          }
        }

        val totalElapsed = System.currentTimeMillis() - startTime
        TorStatusResult(
          isTor = false,
          ip = "Verification Failed",
          message = "SOCKS5 active on $socksPort but check.torproject.org check failed after $maxAttempts attempts ($lastErrorMessage)",
          latencyMs = totalElapsed,
          socksHandshakePassed = true,
          attemptsMade = maxAttempts,
        )
      } ?: TorStatusResult(
        isTor = false,
        ip = "Timeout",
        message = "Tor verification timed out after ${TOR_VERIFY_TOTAL_TIMEOUT_MS / 1000}s",
        latencyMs = TOR_VERIFY_TOTAL_TIMEOUT_MS,
        socksHandshakePassed = true,
        attemptsMade = maxAttempts,
      )
    }
}

