package com.remmi.adblock

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Native Messaging Delegate for Remmi GeckoView WebExtension.
 * Dedicated to threat neutralization, tracker blocking, and click transparency DOM inspection.
 *
 * CRITICAL ARCHITECTURAL INVARIANT:
 * WebExtension proxy authority has been completely removed.
 * Native Gecko layer (GeckoRuntime / GeckoSession) is the SOLE authoritative manager of proxy
 * routing, Tor SOCKS5 isolation, and network hardening.
 *
 * CONCURRENCY & ISOLATION INVARIANT:
 * Every asynchronous request contains (tabId, sessionId, requestId) tracking.
 * Responses are routed directly and deterministically to the originating tab/request,
 * preventing cross-tab state leakage or callback overwrites.
 */
enum class ExtensionState {
  NOT_REGISTERED,
  REGISTERED,
  CONNECTED,
  DISCONNECTED,
  FAILED
}

class BlockExtension private constructor(private val adblockBridge: AdblockBridge) : WebExtension.MessageDelegate {

  var siteSecurityProvider: ((String) -> Boolean)? = null
  // Global listeners (for passive threat and click interception events)
  private val threatListeners = CopyOnWriteArraySet<(url: String, type: String) -> Unit>()
  private val htmlListeners = CopyOnWriteArraySet<(url: String, html: String) -> Unit>()
  private val clickListeners = CopyOnWriteArraySet<(candidates: List<JSONObject>, hasOverlay: Boolean, intercepted: Boolean, pageUrl: String) -> Unit>()

  // Per-request / per-tab isolated callback registries
  private val pendingHtmlRequests = ConcurrentHashMap<String, (url: String, html: String) -> Unit>()
  private val pendingClickRequests = ConcurrentHashMap<String, (candidates: List<JSONObject>, hasOverlay: Boolean, intercepted: Boolean, pageUrl: String) -> Unit>()

  // Legacy single-property compatibility with thread safety - does NOT wipe multi-listener registry
  @Volatile
  private var legacyThreatListener: ((url: String, type: String) -> Unit)? = null
  @Volatile
  private var legacyHtmlListener: ((url: String, html: String) -> Unit)? = null
  @Volatile
  private var legacyClickListener: ((candidates: List<JSONObject>, hasOverlay: Boolean, intercepted: Boolean, pageUrl: String) -> Unit)? = null

  var onThreatNeutralized: ((url: String, type: String) -> Unit)?
    get() = legacyThreatListener
    set(value) {
      legacyThreatListener = value
    }

  var onHtmlExtracted: ((url: String, html: String) -> Unit)?
    get() = legacyHtmlListener
    set(value) {
      legacyHtmlListener = value
    }

  var onClickInspected: ((candidates: List<JSONObject>, hasOverlay: Boolean, intercepted: Boolean, pageUrl: String) -> Unit)?
    get() = legacyClickListener
    set(value) {
      legacyClickListener = value
    }

  fun addThreatListener(listener: (url: String, type: String) -> Unit) = threatListeners.add(listener)
  fun removeThreatListener(listener: (url: String, type: String) -> Unit) = threatListeners.remove(listener)

  fun addHtmlListener(listener: (url: String, html: String) -> Unit) = htmlListeners.add(listener)
  fun removeHtmlListener(listener: (url: String, html: String) -> Unit) = htmlListeners.remove(listener)

  fun addClickListener(listener: (candidates: List<JSONObject>, hasOverlay: Boolean, intercepted: Boolean, pageUrl: String) -> Unit) = clickListeners.add(listener)
  fun removeClickListener(listener: (candidates: List<JSONObject>, hasOverlay: Boolean, intercepted: Boolean, pageUrl: String) -> Unit) = clickListeners.remove(listener)

  private val _extensionState = MutableStateFlow(ExtensionState.NOT_REGISTERED)
  val extensionState: StateFlow<ExtensionState> = _extensionState.asStateFlow()

  @Volatile
  private var activePort: WebExtension.Port? = null
  private val portLock = Any()

  fun setExtensionRegistered() {
    if (_extensionState.value == ExtensionState.NOT_REGISTERED) {
      _extensionState.value = ExtensionState.REGISTERED
    }
  }

  fun setExtensionFailed(reason: String) {
    _extensionState.value = ExtensionState.FAILED
    log("[WEBEXT] Extension registration failed: $reason")
  }

  override fun onMessage(
    nativeApp: String,
    message: Any,
    sender: WebExtension.MessageSender
  ): org.mozilla.geckoview.GeckoResult<Any>? {
    if (message is JSONObject) {
      val type = message.optString("type")
      if (type == "SHOULD_BLOCK") {
        val url = message.optString("url")
        val sourceUrl = message.optString("sourceUrl")
        val resourceType = message.optString("resourceType")
        
        val result = org.mozilla.geckoview.GeckoResult<Any>()
        CoroutineScope(Dispatchers.IO).launch {
            val sourceHost = try {
                if (sourceUrl.isNotEmpty()) java.net.URI(sourceUrl).host?.lowercase()?.trim() else null
            } catch (e: Exception) { null }
            val bypass = sourceHost != null && siteSecurityProvider?.invoke(sourceHost) == true
            
            val blocked = if (bypass) false else adblockBridge.shouldBlock(url, sourceUrl, resourceType)
            val responseObj = JSONObject().apply { put("cancel", blocked) }
            result.complete(responseObj)
        }
        return result
      }
    }
    return null
  }

  override fun onConnect(port: WebExtension.Port) {
    log("[WEBEXT] Native port connected: ${port.name}")
    synchronized(portLock) {
      activePort = port
      _extensionState.value = ExtensionState.CONNECTED
    }

    port.setDelegate(object : WebExtension.PortDelegate {
      override fun onPortMessage(message: Any, p: WebExtension.Port) {
        if (message is JSONObject) {
          val type = message.optString("type").ifEmpty { message.optString("action") }
          val url = message.optString("url")
          val category = message.optString("category").ifEmpty { message.optString("type", "script") }
          val msgText = message.optString("message").ifEmpty { message.optString("msg") }
          val status = message.optString("status")
          val requestId = message.optString("requestId")
          val tabId = message.optString("tabId")

          when (type) {
            "PORT_STATUS" -> {
              val role = message.optString("role", "AD_TRACKER_BLOCKER_ONLY")
              log("[WEBEXT] Port status: $status (role=$role)")
            }
            "CLICK_INSPECTION_RESULT" -> {
              val candidatesArray = message.optJSONArray("candidates")
              val hasOverlay = message.optBoolean("hasOverlay", false)
              val intercepted = message.optBoolean("intercepted", false)
              val pageUrl = message.optString("pageUrl", "")
              val candidatesList = mutableListOf<JSONObject>()
              if (candidatesArray != null) {
                for (i in 0 until candidatesArray.length()) {
                  val c = candidatesArray.optJSONObject(i)
                  if (c != null) candidatesList.add(c)
                }
              }
              log("[WEBEXT] Click inspection received (req=$requestId, tab=$tabId): ${candidatesList.size} candidates (hasOverlay=$hasOverlay, intercepted=$intercepted)")

              // Route to explicit caller first
              if (requestId.isNotEmpty()) {
                val targeted = pendingClickRequests.remove(requestId)
                if (targeted != null) {
                  try {
                    targeted(candidatesList, hasOverlay, intercepted, pageUrl)
                  } catch (e: Exception) {
                    log("[WEBEXT] Targeted click callback error: ${e.message}")
                  }
                }
              }

              // Also dispatch to global and legacy listeners
              legacyClickListener?.let { listener ->
                try {
                  listener(candidatesList, hasOverlay, intercepted, pageUrl)
                } catch (e: Exception) {
                  log("[WEBEXT] Legacy click listener error: ${e.message}")
                }
              }
              clickListeners.forEach { listener ->
                try {
                  listener(candidatesList, hasOverlay, intercepted, pageUrl)
                } catch (e: Exception) {
                  log("[WEBEXT] Click listener error: ${e.message}")
                }
              }
            }
            "BLOCKED", "blocked" -> {
              if (url.isNotEmpty()) {
                log("[TRACKER] Neutralized: $url ($category)")
                adblockBridge.totalBlockedCount.incrementAndGet()
                legacyThreatListener?.let { listener ->
                  try {
                    listener(url, category)
                  } catch (e: Exception) {
                    log("[WEBEXT] Legacy threat listener error: ${e.message}")
                  }
                }
                threatListeners.forEach { listener ->
                  try {
                    listener(url, category)
                  } catch (e: Exception) {
                    log("[WEBEXT] Threat listener error: ${e.message}")
                  }
                }
              }
            }
            "EXTRACTED_HTML", "extracted_html" -> {
              val html = message.optString("html")

              var targeted: ((String, String) -> Unit)? = null
              if (requestId.isNotEmpty()) {
                targeted = pendingHtmlRequests.remove(requestId)
              }
              if (tabId.isNotEmpty()) {
                val byTab = pendingHtmlRequests.remove(tabId)
                if (targeted == null) targeted = byTab
              }

              if (targeted != null) {
                try {
                  targeted(url, html)
                } catch (e: Exception) {
                  log("[WEBEXT] Targeted html callback error: ${e.message}")
                }
              }

              // Also dispatch to global and legacy listeners
              legacyHtmlListener?.let { listener ->
                try {
                  listener(url, html)
                } catch (e: Exception) {
                  log("[WEBEXT] Legacy html listener error: ${e.message}")
                }
              }
              htmlListeners.forEach { listener ->
                try {
                  listener(url, html)
                } catch (e: Exception) {
                  log("[WEBEXT] Html listener error: ${e.message}")
                }
              }
            }
            "LOG", "log" -> {
              if (msgText.isNotEmpty()) {
                log(msgText)
              }
            }
            else -> {
              log("[WEBEXT] Raw message received: $message")
            }
          }
        } else {
          log("[WEBEXT] Non-JSON message received: $message")
        }
      }

      override fun onDisconnect(p: WebExtension.Port) {
        log("[WEBEXT] Native port disconnected")
        synchronized(portLock) {
          if (activePort == p) {
            activePort = null
            _extensionState.value = ExtensionState.DISCONNECTED
          }
        }
        pendingHtmlRequests.clear()
        pendingClickRequests.clear()
      }
    })
  }

  fun extractTabHtml(
    tabId: String? = null,
    sessionId: String? = null,
    requestId: String = UUID.randomUUID().toString(),
    callback: ((url: String, html: String) -> Unit)? = null
  ) {
    if (callback != null) {
      pendingHtmlRequests[requestId] = callback
      if (tabId != null) {
        pendingHtmlRequests[tabId] = callback
      }
    }

    val msg = JSONObject().apply {
      put("type", "EXTRACT_HTML")
      put("action", "extract_html")
      put("requestId", requestId)
      if (tabId != null) put("tabId", tabId)
      if (sessionId != null) put("sessionId", sessionId)
    }

    synchronized(portLock) {
      val currentPort = activePort
      if (currentPort != null) {
        try {
          currentPort.postMessage(msg)
        } catch (e: Exception) {
          log("[WEBEXT] Could not send extract_html: ${e.message}")
          if (callback != null) {
            pendingHtmlRequests.remove(requestId)
            if (tabId != null) pendingHtmlRequests.remove(tabId)
          }
        }
      } else {
        if (callback != null) {
          pendingHtmlRequests.remove(requestId)
          if (tabId != null) pendingHtmlRequests.remove(tabId)
        }
      }
    }
  }

  fun executeScript(tabId: String, script: String) {
    val cleanScript = if (script.startsWith("javascript:", ignoreCase = true)) script.substring(11) else script
    val msg = JSONObject().apply {
      put("type", "EXECUTE_SCRIPT")
      put("tabId", tabId)
      put("script", cleanScript)
    }
    synchronized(portLock) {
      val currentPort = activePort
      if (currentPort != null) {
        try {
          currentPort.postMessage(msg)
        } catch (e: Exception) {
          log("[WEBEXT] Could not send execute_script: ${e.message}")
        }
      }
    }
  }

  fun extractActiveTabHtml() {
    extractTabHtml(null, null, UUID.randomUUID().toString(), null)
  }

  companion object {
    private const val TAG = "BlockExtension"

    fun log(message: String) {
      com.remmi.browser.util.DebugLogManager.log(message)
    }

    val debugLogs: StateFlow<List<String>> = com.remmi.browser.util.DebugLogManager.logs

    fun clearLogs() {
      com.remmi.browser.util.DebugLogManager.clear()
    }

    @Volatile
    private var INSTANCE: BlockExtension? = null

    fun getInstance(bridge: AdblockBridge = AdblockBridge.getInstance()): BlockExtension {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: BlockExtension(bridge).also { INSTANCE = it }
      }
    }
  }
}
