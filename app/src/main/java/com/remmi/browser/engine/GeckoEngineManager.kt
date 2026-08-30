package com.remmi.browser.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.remmi.adblock.AdblockBridge
import com.remmi.adblock.BlockExtension
import com.remmi.browser.model.WebContextMenuData
import com.remmi.browser.security.AntiFingerprint
import com.remmi.browser.security.ContainerType
import com.remmi.browser.security.CurrentTorRoute
import com.remmi.browser.security.NetworkHardening
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.SecurityLevel
import com.remmi.browser.util.PdfPrintHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse
import java.io.File
import kotlin.coroutines.resume

sealed class CloseResult {
  object Success : CloseResult()
  object NotFound : CloseResult()
  object AlreadyClosed : CloseResult()
  data class Failed(val error: Throwable) : CloseResult()
}

/**
 * Interface for tab event subscriptions.
 * Decouples Compose UI layers from raw GeckoView delegates.
 */
interface GeckoTabCallbacks {
  fun onUrlChange(url: String) {}
  fun onTitleChange(title: String) {}
  fun onProgressChange(progress: Int) {}
  fun onLoadingChange(isLoading: Boolean) {}
  fun onSecurityChange(isSecure: Boolean) {}
  fun onNavStateChange(canGoBack: Boolean, canGoForward: Boolean) {}
  fun onTrackerBlocked(url: String, type: String) {}
  fun onExternalResponse(response: WebResponse) {}
  fun onContextMenu(data: WebContextMenuData) {}
  fun onScrollChanged(scrollX: Int, scrollY: Int, isScrollingDown: Boolean) {}
}

/**
 * GeckoEngineManager & GeckoSessionController
 * The EXCLUSIVE SINGLE OWNER of:
 * - GeckoRuntime lifecycle
 * - GeckoSession creation, registry, and destruction (NEVER exposed to UI callers)
 * - Delegate registration and thread enforcement
 * - View attachment/detachment for GeckoView
 * - Navigation commands (loadUrl, goBack, goForward, reload, stopLoading, findInPage, evaluateJs, print, exportPdf)
 *
 * Enforces strictly that all GeckoSession interactions execute on the Android Main thread.
 */
class GeckoEngineManager private constructor(private val context: Context) {

  var runtime: GeckoRuntime? = null
    private set

  val blockExtension = BlockExtension.getInstance()
  init {
    blockExtension.siteSecurityProvider = { host ->
      val policy = com.remmi.browser.security.SiteSecurityPolicyManager.getInstance(context).getPolicyForHost(host)
      policy.cookiePolicy == "ALLOW"
    }
  }
  enum class GeckoInitState {
    NOT_STARTED,
    INITIALIZING,
    READY,
    FAILED
  }

  private val _initState = MutableStateFlow(GeckoInitState.NOT_STARTED)
  val initState: StateFlow<GeckoInitState> = _initState.asStateFlow()

  @Volatile
  private var initDeferred: CompletableDeferred<GeckoRuntime>? = null

  private var currentProfile: PrivacyProfile = PrivacyProfile.SHIELD
  private val activeSessions = mutableMapOf<String, GeckoSession>()
  private val sessionCallbacks = mutableMapOf<String, GeckoTabCallbacks>()
  private val sessionNavStates = mutableMapOf<String, Pair<Boolean, Boolean>>()
  private val mainHandler = Handler(Looper.getMainLooper())

  private fun assertMainThread(operation: String) {
    val isMain = Looper.myLooper() == Looper.getMainLooper()
    Log.d(TAG, "[GECKO] operation=$operation thread=${if (isMain) "main" else "ILLEGAL_${Thread.currentThread().name}"}")
    check(isMain) { "Gecko operation $operation MUST be called on the Main thread! (Current: ${Thread.currentThread().name})" }
  }

  fun initializeRuntimeAsync() {
    if (_initState.value != GeckoInitState.NOT_STARTED && _initState.value != GeckoInitState.FAILED) {
      return
    }

    _initState.value = GeckoInitState.INITIALIZING
    Log.i(TAG, "STATE_LOG: GECKO_INIT_START (time=${android.os.SystemClock.elapsedRealtime()})")

    CoroutineScope(Dispatchers.Main.immediate).launch {
      try {
        initializeRuntimeInternal()
        _initState.value = GeckoInitState.READY
        Log.i(TAG, "STATE_LOG: GECKO_INIT_READY (time=${android.os.SystemClock.elapsedRealtime()})")
      } catch (t: Throwable) {
        _initState.value = GeckoInitState.FAILED
        Log.e(TAG, "STATE_LOG: GECKO_INIT_FAILED (time=${android.os.SystemClock.elapsedRealtime()}) error=${t.message}")
      }
    }
  }

  private suspend fun initializeRuntimeInternal() {
    assertMainThread("INITIALIZE_RUNTIME_INTERNAL")
    if (runtime != null) {
      _initState.value = GeckoInitState.READY
      return
    }

    val startTime = android.os.SystemClock.elapsedRealtime()
    Log.i(TAG, "Initializing GeckoRuntime with Process Isolation & WebRender...")

    // Clean up any stale config files
    try {
      val oldConfig = java.io.File(context.filesDir, "gv-config.yaml")
      if (oldConfig.exists()) {
        oldConfig.delete()
      }
    } catch (_: Exception) {}

    val settings = GeckoRuntimeSettings.Builder()
      .aboutConfigEnabled(com.remmi.browser.BuildConfig.DEBUG)
      .consoleOutput(com.remmi.browser.BuildConfig.DEBUG)
      .build()
      
    try {
        settings.setLnaEnabled(false)
    } catch (e: Exception) {
        Log.w(TAG, "LNA API not available on this GeckoView version", e)
    }

    val rt = GeckoRuntime.create(context, settings)

    // Register WebExtension for native ad/tracker blocking and secondary proxy synchronization
    try {
      val extensionUri = "resource://android/assets/extensions/remmi_engine_extension/"
      
      val installPromptHandler = { ext: WebExtension? ->
        if (ext != null) {
          rt.webExtensionController.setAllowedInPrivateBrowsing(ext, true)
          ext.setMessageDelegate(blockExtension, "remmi_engine_extension")
          blockExtension.setExtensionRegistered()
          Log.i(TAG, "Remmi WebExtension registered successfully: ${ext.id}")
          com.remmi.browser.util.DebugLogManager.log("[WEBEXT] Registered (ID: ${ext.id})")
        } else {
          blockExtension.setExtensionFailed("WebExtension controller returned null")
          com.remmi.browser.util.DebugLogManager.log("[WEBEXT] WARNING: WebExtension controller returned null")
        }
      }

      val failureHandler = { throwable: Throwable? ->
        Log.w(TAG, "ensureBuiltIn notice, trying fallback install", throwable)
        try {
          rt.webExtensionController
            .installBuiltIn(extensionUri)
            .accept(
              { ext -> installPromptHandler(ext) },
              { fallbackErr ->
                val reason = fallbackErr?.message ?: "Unknown fallback error"
                blockExtension.setExtensionFailed(reason)
                com.remmi.browser.util.DebugLogManager.log("[WEBEXT] Fallback install failed: $reason")
              }
            )
        } catch (fbEx: Exception) {
          val reason = fbEx.message ?: "Exception"
          blockExtension.setExtensionFailed(reason)
          com.remmi.browser.util.DebugLogManager.log("[WEBEXT] Fallback exception: $reason")
        }
      }

      suspendCancellableCoroutine<Unit> { cont ->
        rt.webExtensionController
          .ensureBuiltIn(extensionUri, "extension@remmi.browser")
          .accept(
            { ext: WebExtension? -> installPromptHandler(ext); cont.resume(Unit) },
            { throwable: Throwable? -> failureHandler(throwable); cont.resume(Unit) }
          )
      }
    } catch (e: Exception) {
      Log.w(TAG, "WebExtension installation skipped: ${e.message}")
      blockExtension.setExtensionFailed(e.message ?: "Skipped")
      com.remmi.browser.util.DebugLogManager.log("[WEBEXT] Installation exception: ${e.message}")
    }

    runtime = rt
    applyPrivacyProfile(PrivacyProfile.SHIELD)
    val duration = android.os.SystemClock.elapsedRealtime() - startTime
    Log.i(TAG, "GeckoRuntime initialization completed in ${duration}ms (READY)")
  }

  suspend fun applyPrivacyProfile(
    profile: PrivacyProfile,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    socksPort: Int? = CurrentTorRoute.currentSocksPort,
    generation: Long = CurrentTorRoute.currentGeneration,
    settings: com.remmi.browser.storage.BrowserSettings? = null,
  ): Boolean {
    currentProfile = profile
    val rt = runtime ?: return false
    val browserSettings = settings ?: com.remmi.browser.storage.SettingsRepository.getInstance(context).settings.value

    return when (profile) {
      PrivacyProfile.GHOST -> {
        val port = socksPort ?: return false
        NetworkHardening.applyTorNetworkSettings(rt, port, generation, browserSettings)
      }
      PrivacyProfile.SHIELD -> {
        NetworkHardening.applyShieldNetworkSettings(rt, generation, browserSettings)
      }
      PrivacyProfile.INCOGNITO -> {
        NetworkHardening.applyShieldNetworkSettings(rt, generation, browserSettings)
      }
    }
  }

  fun updateGlobalPreferences(settings: com.remmi.browser.storage.BrowserSettings) {
    val rt = runtime ?: return
    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
      if (CurrentTorRoute.isGhostActive) {
        val port = CurrentTorRoute.currentSocksPort ?: return@launch
        NetworkHardening.applyTorNetworkSettings(rt, port, CurrentTorRoute.currentGeneration, settings)
      } else {
        NetworkHardening.applyShieldNetworkSettings(rt, CurrentTorRoute.currentGeneration, settings)
      }
    }
    
    // Ensure site-specific overrides have precedence (P1-2)
    val currentTabs = TabManager.getInstance().tabs.value
    for (tab in currentTabs) {
      if (tab.url.isNotBlank() && tab.url != "about:blank") {
        try {
          val host = java.net.URI(if (tab.url.contains("://")) tab.url else "https://${tab.url}").host
          if (!host.isNullOrBlank()) {
            applySiteSecurityPolicy(tab.id, host)
          }
        } catch (_: Exception) {}
      }
    }
  }

  fun applySiteSecurityPolicy(tabId: String, host: String) {
    if (host.isBlank()) return
    val policy = com.remmi.browser.security.SiteSecurityPolicyManager.getInstance(context).getPolicyForHost(host)
    val tab = TabManager.getInstance().getTab(tabId)
    val profile = tab?.profile ?: currentProfile
    val securityLevel = policy.customSecurityLevel ?: tab?.securityLevel ?: SecurityLevel.STANDARD

    onMainSession(tabId, "APPLY_SITE_SECURITY_POLICY") { session ->
      session.settings.apply {
        allowJavascript = policy.javascriptEnabled ?: securityLevel.javascriptEnabled
        useTrackingProtection = (policy.cookiePolicy != "ALLOW")
        suspendMediaWhenInactive = !policy.autoplayAllowed
      }
      AntiFingerprint.configureGeckoSession(session, profile, securityLevel)
      Log.d(TAG, "Applied site security policy for host '$host' to tab $tabId (js=${session.settings.allowJavascript}, tp=${session.settings.useTrackingProtection}, autoplay=${policy.autoplayAllowed})")
    }
  }

  fun applySiteSecurityPolicyToMatchingTabs(host: String) {
    val cleanHost = host.lowercase().trim()
    val currentTabs = TabManager.getInstance().tabs.value
    for (tab in currentTabs) {
      val tabHost = try {
        java.net.URI(if (tab.url.contains("://")) tab.url else "https://${tab.url}").host?.lowercase()?.trim()
      } catch (_: Exception) { null }
      if (tabHost == cleanHost) {
        applySiteSecurityPolicy(tab.id, cleanHost)
      }
    }
  }

  suspend fun applyPrivacyProfile(
    profile: PrivacyProfile,
    socksPort: Int?,
    generation: Long,
  ): Boolean {
    return applyPrivacyProfile(profile, SecurityLevel.STANDARD, socksPort, generation)
  }

  fun setTabGhostMode(tabId: String, isGhost: Boolean) {
    // Native Gecko controls tab isolation via Private Browsing session settings
  }

  // --- Internal Session Factory & Wire-up (Strict Main Thread) ---

  private fun createSessionInternal(
    profile: PrivacyProfile,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    containerType: ContainerType = ContainerType.fromProfile(profile),
    isDesktopMode: Boolean = false
  ): GeckoSession {
    assertMainThread("CREATE_SESSION")
    android.util.Log.i(TAG, "STATE_LOG: SESSION_CREATE_START (time=${android.os.SystemClock.elapsedRealtime()})")
    val rt = runtime ?: throw IllegalStateException("GeckoRuntime is not ready yet (state=${_initState.value}). Sessions must be created only after runtime readiness.")
    
    val isPrivateContainer = containerType != ContainerType.NORMAL || profile == PrivacyProfile.INCOGNITO || profile == PrivacyProfile.GHOST
    val settings = GeckoSessionSettings.Builder()
      .usePrivateMode(isPrivateContainer)
      .useTrackingProtection(true)
      .suspendMediaWhenInactive(true)
      .build()
      
    val session = GeckoSession(settings)

    session.settings.apply {
      userAgentMode = if (isDesktopMode || profile == PrivacyProfile.GHOST) {
        GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
      } else {
        GeckoSessionSettings.USER_AGENT_MODE_MOBILE
      }
      viewportMode = if (isDesktopMode) {
        GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
      } else {
        GeckoSessionSettings.VIEWPORT_MODE_MOBILE
      }
      allowJavascript = securityLevel.javascriptEnabled
    }

    AntiFingerprint.configureGeckoSession(session, profile, securityLevel)
    android.util.Log.i(TAG, "STATE_LOG: SESSION_OPEN (time=${android.os.SystemClock.elapsedRealtime()})")
    session.open(rt)
    return session
  }

  private fun getOrCreateSessionInternal(
    tabId: String,
    profile: PrivacyProfile,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    containerType: ContainerType = ContainerType.fromProfile(profile),
    isDesktopMode: Boolean = false,
  ): GeckoSession {
    assertMainThread("GET_OR_CREATE_INTERNAL id=$tabId")
    val existing = activeSessions[tabId]
    if (existing != null) {
      if (existing.isOpen) {
        return existing
      }
      try {
        existing.navigationDelegate = null
        existing.progressDelegate = null
        existing.contentDelegate = null
        existing.close()
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] Cleanup of defunct session notice on tabId=$tabId: ${e.message}")
      }
    }

    val newSession = createSessionInternal(profile, securityLevel, containerType, isDesktopMode)
    activeSessions[tabId] = newSession
    sessionNavStates[tabId] = Pair(false, false)

    // Wire Navigation delegate
    newSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
      override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
        val url = request.uri ?: ""
        val tab = TabManager.getInstance().getTab(tabId)
        val isGhost = (tab?.profile == PrivacyProfile.GHOST) || (currentProfile == PrivacyProfile.GHOST)
        
        val check = com.remmi.browser.security.NavigationSecurityAuthority.validateAndSanitizeNavigation(url, isGhost)
        when (check.decision) {
            com.remmi.browser.security.NavigationDecision.BLOCK -> {
                Log.e(TAG, "onLoadRequest blocked navigation: ${check.reason}")
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            com.remmi.browser.security.NavigationDecision.SANITIZE_AND_LOAD,
            com.remmi.browser.security.NavigationDecision.REDIRECT_SEARCH -> {
                if (check.sanitizedUrl != null && check.sanitizedUrl != url) {
                    session.loadUri(check.sanitizedUrl)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
            }
            com.remmi.browser.security.NavigationDecision.ALLOW -> {
                // proceed
            }
        }
        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
      }

      override fun onLocationChange(
        session: GeckoSession,
        url: String?,
        perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
        hasUserGesture: Boolean
      ) {
        url?.let {
          if (it.isNotBlank() && it != "about:blank") {
            try {
              val host = java.net.URI(if (it.contains("://")) it else "https://$it").host
              if (!host.isNullOrBlank()) {
                applySiteSecurityPolicy(tabId, host)
              }
            } catch (_: Exception) {}
            sessionCallbacks[tabId]?.onUrlChange(it)
          }
        }
      }

      override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        val current = sessionNavStates[tabId] ?: Pair(false, false)
        val updated = current.copy(first = canGoBack)
        sessionNavStates[tabId] = updated
        sessionCallbacks[tabId]?.onNavStateChange(updated.first, updated.second)
      }

      override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
        val current = sessionNavStates[tabId] ?: Pair(false, false)
        val updated = current.copy(second = canGoForward)
        sessionNavStates[tabId] = updated
        sessionCallbacks[tabId]?.onNavStateChange(updated.first, updated.second)
      }
    }

    // Wire Progress delegate
    newSession.progressDelegate = object : GeckoSession.ProgressDelegate {
      override fun onPageStart(session: GeckoSession, url: String) {
        sessionCallbacks[tabId]?.onLoadingChange(true)
        sessionCallbacks[tabId]?.onProgressChange(10)
      }

      override fun onPageStop(session: GeckoSession, success: Boolean) {
        sessionCallbacks[tabId]?.onLoadingChange(false)
        sessionCallbacks[tabId]?.onProgressChange(100)
      }

      override fun onProgressChange(session: GeckoSession, progress: Int) {
        sessionCallbacks[tabId]?.onProgressChange(progress)
      }

      override fun onSecurityChange(
        session: GeckoSession,
        securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
      ) {
        sessionCallbacks[tabId]?.onSecurityChange(securityInfo.isSecure)
      }
    }

    // Wire Content delegate
    newSession.contentDelegate = object : GeckoSession.ContentDelegate {
      override fun onTitleChange(session: GeckoSession, title: String?) {
        title?.let {
          if (it.isNotBlank() && it != "about:blank") {
            sessionCallbacks[tabId]?.onTitleChange(it)
          }
        }
      }

      override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
        sessionCallbacks[tabId]?.onExternalResponse(response)
      }

      override fun onContextMenu(
        session: GeckoSession,
        screenX: Int,
        screenY: Int,
        element: GeckoSession.ContentDelegate.ContextElement
      ) {
        val hasLink = !element.linkUri.isNullOrBlank()
        val hasImage = element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE || !element.srcUri.isNullOrBlank()
        if (hasLink || hasImage) {
          sessionCallbacks[tabId]?.onContextMenu(
            WebContextMenuData(
              linkUri = element.linkUri,
              srcUri = element.srcUri,
              altText = element.altText,
              title = element.title,
              type = element.type,
            )
          )
        }
      }
    }

    // Wire Scroll delegate
    newSession.scrollDelegate = object : GeckoSession.ScrollDelegate {
      private var lastScrollY = 0
      override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
        val isScrollingDown = scrollY > lastScrollY && scrollY > 20
        lastScrollY = scrollY
        sessionCallbacks[tabId]?.onScrollChanged(scrollX, scrollY, isScrollingDown)
      }
    }

    // Set WebExtension threat tracker callback
    blockExtension.onThreatNeutralized = { threatUrl, threatType ->
      sessionCallbacks[tabId]?.onTrackerBlocked(threatUrl, threatType)
    }

    return newSession
  }

  /**
   * Internal session execution runner confined strictly to the Android Main thread.
   * Completely encapsulates activeSessions and isolates GeckoSession manipulation.
   */
  private suspend fun <T> withSession(
    tabId: String,
    operation: String = "OPERATION",
    action: (GeckoSession) -> T,
  ): T? = withContext(Dispatchers.Main.immediate) {
    assertMainThread("WITH_SESSION op=$operation id=$tabId")
    val session = activeSessions[tabId] ?: return@withContext null
    try {
      action(session)
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] withSession op=$operation failed on tabId=$tabId: ${e.message}")
      null
    }
  }

  /**
   * Synchronous gateway for fire-and-forget UI calls, automatically dispatches to Main thread.
   */
  private fun onMainSession(
    tabId: String,
    operation: String,
    action: (GeckoSession) -> Unit,
  ) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { onMainSession(tabId, operation, action) }
      return
    }
    assertMainThread("MAIN_SESSION op=$operation id=$tabId")
    val session = activeSessions[tabId] ?: return
    try {
      action(session)
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] onMainSession op=$operation error on tabId=$tabId: ${e.message}")
    }
  }

  // --- View Attachment & Lifecycle Control ---

  suspend fun attachView(
    tabId: String,
    geckoView: GeckoView,
    profile: PrivacyProfile,
    isDesktopMode: Boolean,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
    containerType: ContainerType = ContainerType.fromProfile(profile),
    callbacks: GeckoTabCallbacks,
  ) = withContext(Dispatchers.Main.immediate) {
    assertMainThread("ATTACH_VIEW id=$tabId")
    sessionCallbacks[tabId] = callbacks
    
    // Do NOT initialize GeckoRuntime here. It should only happen in loadUrl for actual navigations.
    
    if (_initState.value != GeckoInitState.READY) {
      Log.d(TAG, "[GECKO] attachView suspending for runtime readiness on tabId=$tabId")
      _initState.first { it == GeckoInitState.READY || it == GeckoInitState.FAILED }
    }
    
    if (_initState.value == GeckoInitState.FAILED || runtime == null) {
      Log.e(TAG, "[GECKO] attachView failed: runtime is not ready")
      return@withContext
    }
    val session = getOrCreateSessionInternal(tabId, profile, securityLevel, containerType, isDesktopMode)
    try {
      session.setActive(true)
      if (geckoView.session != session) {
        geckoView.setSession(session)
      }
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] attachView error on tabId=$tabId: ${e.message}")
    }
  }

  suspend fun detachView(
    tabId: String,
    geckoView: GeckoView? = null,
  ) = withContext(Dispatchers.Main.immediate) {
    assertMainThread("DETACH_VIEW id=$tabId")
    try {
      geckoView?.releaseSession()
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] releaseSession error: ${e.message}")
    }
    withSession(tabId, "DETACH_SET_INACTIVE") { session ->
      try {
        session.setActive(false)
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] setActive(false) notice: ${e.message}")
      }
    }
  }

  suspend fun setTabActive(tabId: String, active: Boolean) = withContext(Dispatchers.Main.immediate) {
    assertMainThread("SET_TAB_ACTIVE id=$tabId active=$active")
    withSession(tabId, "SET_TAB_ACTIVE") { session ->
      try {
        session.setActive(active)
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] setActive($active) notice: ${e.message}")
      }
    }
  }

  suspend fun updateTabSettings(
    tabId: String,
    isDesktopMode: Boolean,
    profile: PrivacyProfile,
    securityLevel: SecurityLevel = SecurityLevel.STANDARD,
  ) = withContext(Dispatchers.Main.immediate) {
    assertMainThread("UPDATE_TAB_SETTINGS id=$tabId")
    withSession(tabId, "UPDATE_TAB_SETTINGS") { session ->
      try {
        session.settings.userAgentMode = if (isDesktopMode || profile == PrivacyProfile.GHOST) {
          GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        } else {
          GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        }
        session.settings.viewportMode = if (isDesktopMode) {
          GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        } else {
          GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        }
        session.settings.allowJavascript = securityLevel.javascriptEnabled
        AntiFingerprint.configureGeckoSession(session, profile, securityLevel)
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] updateTabSettings notice: ${e.message}")
      }
    }
  }

  // --- High-Level Navigation & Session Commands ---

  fun loadUrl(tabId: String, url: String) {
    if (url.isBlank()) return
    val tab = TabManager.getInstance().getTab(tabId)
    val isGhost = (tab?.profile == PrivacyProfile.GHOST) || (currentProfile == PrivacyProfile.GHOST)
    val check = com.remmi.browser.security.NavigationSecurityAuthority.validateAndSanitizeNavigation(url, isGhost)
    if (check.decision == com.remmi.browser.security.NavigationDecision.BLOCK) {
      Log.w(TAG, "Blocked navigation to '$url' reason: ${check.reason}")
      return
    }
    val targetUrl = check.sanitizedUrl ?: url
    val host = try { java.net.URI(if (targetUrl.contains("://")) targetUrl else "https://$targetUrl").host ?: "" } catch (_: Exception) { "" }
    if (host.isNotBlank()) {
      applySiteSecurityPolicy(tabId, host)
    }
    
    if (Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { loadUrl(tabId, targetUrl) }
      return
    }
    assertMainThread("LOAD_URL id=$tabId")
    android.util.Log.i(TAG, "STATE_LOG: FIRST_PAGE_START (time=${android.os.SystemClock.elapsedRealtime()})")
    
    if (_initState.value == GeckoInitState.NOT_STARTED) {
      Log.d(TAG, "[GECKO] loadUrl requesting init on tabId=$tabId")
      initializeRuntimeAsync()
    }

    if (runtime == null || _initState.value != GeckoInitState.READY) {
      Log.d(TAG, "[GECKO] loadUrl runtime not ready yet, deferring 50ms for tabId=$tabId")
      mainHandler.postDelayed({ loadUrl(tabId, targetUrl) }, 50)
      return
    }
    val session = activeSessions[tabId] ?: run {
      val profile = tab?.profile ?: currentProfile
      val secLevel = tab?.securityLevel ?: SecurityLevel.STANDARD
      val container = tab?.containerType ?: ContainerType.fromProfile(profile)
      val isDesktop = tab?.isDesktopMode ?: false
      getOrCreateSessionInternal(tabId, profile, secLevel, container, isDesktop)
    }
    try {
      session.loadUri(targetUrl)
    } catch (e: Exception) {
      Log.w(TAG, "[GECKO] loadUrl error on tabId=$tabId: ${e.message}")
    }
  }

  fun reload(tabId: String) {
    onMainSession(tabId, "RELOAD") { session ->
      session.reload()
    }
  }

  fun stopLoading(tabId: String) {
    onMainSession(tabId, "STOP_LOADING") { session ->
      session.stop()
    }
  }

  fun goBack(tabId: String) {
    onMainSession(tabId, "GO_BACK") { session ->
      session.goBack()
    }
  }

  fun goForward(tabId: String) {
    onMainSession(tabId, "GO_FORWARD") { session ->
      session.goForward()
    }
  }

  fun findInPage(tabId: String, query: String, backwards: Boolean = false) {
    onMainSession(tabId, "FIND_IN_PAGE") { session ->
      val flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else 0
      session.finder.find(query, flags)
    }
  }

  fun clearFindInPage(tabId: String) {
    onMainSession(tabId, "CLEAR_FIND_IN_PAGE") { session ->
      session.finder.clear()
    }
  }

  fun executeScript(tabId: String, script: String) {
    com.remmi.adblock.BlockExtension.getInstance().executeScript(tabId, script)
  }

  fun printPage(activityContext: Context, tabId: String, pageTitle: String, onFinished: (() -> Unit)? = null) {
    onMainSession(tabId, "PRINT_PAGE") { session ->
      try {
        PdfPrintHelper.printPage(activityContext, { session.saveAsPdf() }, pageTitle, onFinished)
      } catch (e: Exception) {
        Log.e(TAG, "printPage error: ${e.message}", e)
        Toast.makeText(activityContext, "Print error: ${e.message}", Toast.LENGTH_SHORT).show()
        onFinished?.invoke()
      }
    }
  }

  fun exportPageAsPdf(
    tabId: String,
    pageTitle: String,
    onFinished: ((File?) -> Unit)? = null,
  ) {
    onMainSession(tabId, "EXPORT_PAGE_AS_PDF") { session ->
      try {
        PdfPrintHelper.exportPageAsPdf(context, { session.saveAsPdf() }, pageTitle, onFinished)
      } catch (e: Exception) {
        Log.e(TAG, "exportPageAsPdf error: ${e.message}", e)
        onFinished?.invoke(null)
      }
    }
  }

  suspend fun <T> executeOnSession(tabId: String, block: (GeckoSession) -> T): T? = withContext(Dispatchers.Main.immediate) {
    assertMainThread("EXECUTE_ON_SESSION id=$tabId")
    val session = activeSessions[tabId] ?: return@withContext null
    try {
      block(session)
    } catch (e: Exception) {
      Log.e(TAG, "executeOnSession error on tabId=$tabId: ${e.message}", e)
      null
    }
  }

  suspend fun closeSessionSafely(tabId: String): CloseResult = withContext(Dispatchers.Main.immediate) {
    assertMainThread("CLOSE_SESSION_SAFELY id=$tabId")
    sessionCallbacks.remove(tabId)
    sessionNavStates.remove(tabId)
    val session = activeSessions.remove(tabId)
    if (session == null) {
      Log.d(TAG, "[GECKO] operation=CLOSE_NOT_FOUND id=$tabId thread=main")
      return@withContext CloseResult.NotFound
    }
    try {
      // Null out delegates to eliminate trailing asynchronous callbacks
      session.navigationDelegate = null
      session.progressDelegate = null
      session.contentDelegate = null
      session.close()
      Log.d(TAG, "[GECKO] operation=CLOSE_COMPLETED id=$tabId thread=main")
      CloseResult.Success
    } catch (t: Throwable) {
      Log.w(TAG, "[GECKO] operation=CLOSE_NOTICE id=$tabId thread=main error=${t.message}")
      CloseResult.Success // Soft-success since resources and map entry are detached
    }
  }

  suspend fun closeAllSessionsSafely() = withContext(Dispatchers.Main.immediate) {
    assertMainThread("CLOSE_ALL")
    sessionCallbacks.clear()
    sessionNavStates.clear()
    val sessionsToClose = activeSessions.values.toList()
    activeSessions.clear()
    sessionsToClose.forEach { session ->
      try {
        session.navigationDelegate = null
        session.progressDelegate = null
        session.contentDelegate = null
        session.close()
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] operation=CLOSE_ALL_NOTICE: ${e.message}")
      }
    }
    Log.d(TAG, "[GECKO] operation=CLOSE_ALL_COMPLETED count=${sessionsToClose.size} thread=main")
  }

  /**
   * Complete, atomic destruction of all browser tabs, sessions, delegates, and view bindings.
   * Single source of truth for halting browsing activity during Panic Wipe or full reset.
   */
  suspend fun destroyAllBrowserState(): Boolean = withContext(Dispatchers.Main.immediate) {
    assertMainThread("DESTROY_ALL_BROWSER_STATE")
    try {
      // 1. Clear callbacks and delegates
      sessionCallbacks.clear()
      sessionNavStates.clear()

      // 2. Stop all running sessions and close
      val sessions = activeSessions.values.toList()
      activeSessions.clear()
      var allSessionsStopped = true
      var allSessionsClosed = true
      sessions.forEach { session ->
        try {
          session.stop()
        } catch (e: Exception) {
          Log.w(TAG, "[GECKO] destroyAllBrowserState session stop notice: ${e.message}")
          allSessionsStopped = false
        }
        try {
          session.navigationDelegate = null
          session.progressDelegate = null
          session.contentDelegate = null
          session.setActive(false)
          session.close()
        } catch (e: Exception) {
          Log.w(TAG, "[GECKO] destroyAllBrowserState session close notice: ${e.message}")
          allSessionsClosed = false
        }
      }

      // 3. Notify TabManager to purge tab list
      var tabsCleared = true
      try {
        TabManager.getInstance().closeAllTabs()
      } catch (e: Exception) {
        Log.w(TAG, "[GECKO] destroyAllBrowserState tab close notice: ${e.message}")
        tabsCleared = false
      }

      val success = allSessionsStopped && allSessionsClosed && tabsCleared
      Log.i(TAG, "[GECKO] All browser state destroyed (success=$success, stopped=$allSessionsStopped, closed=$allSessionsClosed, tabs=$tabsCleared, ${sessions.size} sessions closed).")
      success
    } catch (e: Exception) {
      Log.e(TAG, "[GECKO] destroyAllBrowserState encountered error: ${e.message}", e)
      false
    }
  }

  suspend fun clearCookiesAndCacheSafely(): Boolean = withContext(Dispatchers.Main.immediate) {
    closeAllSessionsSafely()
    val rt = runtime ?: return@withContext true
    try {
      suspendCancellableCoroutine { continuation ->
        val geckoResult = rt.storageController.clearData(org.mozilla.geckoview.StorageController.ClearFlags.ALL)
        geckoResult.accept(
          { continuation.resume(true) },
          { err ->
            Log.w(TAG, "Gecko clearData returned error: ${err?.message}")
            continuation.resume(false)
          }
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error clearing Gecko storage data: ${e.message}")
      false
    }
  }

  companion object {
    private const val TAG = "GeckoEngineManager"

    @Volatile
    private var INSTANCE: GeckoEngineManager? = null

    fun getInstance(context: Context): GeckoEngineManager {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: GeckoEngineManager(context.applicationContext).also { INSTANCE = it }
      }
    }
  }
}
