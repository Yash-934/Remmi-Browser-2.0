package com.remmi.browser.security

import android.util.Log
import com.remmi.browser.util.DebugLogManager
import com.remmi.browser.engine.GeckoPreferenceController
import org.mozilla.geckoview.GeckoRuntime

/**
 * Route configuration key used to ensure idempotent proxy dispatch.
 */
data class RouteKey(
  val profile: PrivacyProfile,
  val socksPort: Int,
  val generation: Long,
  val runtimeHash: Int
)

object NetworkHardening {
  private const val TAG = "NetworkHardening"

  @Volatile
  private var lastAppliedRouteKey: RouteKey? = null

  fun getTorPreferences(
    torPort: Int? = CurrentTorRoute.currentSocksPort,
    settings: com.remmi.browser.storage.BrowserSettings? = null
  ): Map<String, Any> {
    require(torPort != null && torPort > 0) { "Valid Tor SOCKS port required (received $torPort)" }
    return mapOf(
      "network.proxy.type" to 1,
      "network.proxy.socks" to "127.0.0.1",
      "network.proxy.socks_port" to torPort,
      "network.proxy.socks_version" to 5,
      "network.proxy.socks_remote_dns" to true,
      "network.proxy.failover_direct" to false, // CRITICAL: zero clearnet leak
      "network.proxy.no_proxies_on" to "",
      "network.trr.mode" to 5, // TRR disabled in Ghost mode: all DNS routed via Tor remote DNS
      "media.peerconnection.enabled" to false, // WebRTC completely blocked
      "media.peerconnection.ice.proxy_only" to true,
      "media.peerconnection.ice.default_address_only" to true,
      "network.dns.disablePrefetch" to true,
      "network.dns.disablePrefetchFromHTTPS" to true,
      "network.dns.blockDotOnion" to false,
      "network.captive-portal-service.enabled" to false,
      "network.http.speculative-parallel-limit" to 0,
      "network.predictor.enabled" to false,
      "browser.places.speculativeConnect.enabled" to false,
      "network.lna.enabled" to false, // Local Network Access blocked
      "network.lna.blocking" to true,
      "dom.security.https_only_mode" to (settings?.httpsOnlyMode ?: true),
      "dom.security.https_only_mode_pbm" to true,
      "security.tls.version.min" to 3, // TLS 1.2 minimum
      "security.tls.version.max" to 4, // TLS 1.3 maximum
      "network.websocket.allowInsecureFromHTTPS" to false, // Block insecure WebSocket on HTTPS
      "security.mixed_content.block_active_content" to true,
      "security.mixed_content.upgrade_display_content" to true,
      "network.dns.echconfig.enabled" to (settings?.encryptedClientHelloEnabled ?: true), // ECH (Encrypted Client Hello)
      "network.dns.use_https_rr_as_alpn" to true,
      "privacy.resistFingerprinting" to true,
      "privacy.resistFingerprinting.letterboxing" to true,
      "privacy.firstparty.isolate" to true,
      "privacy.globalprivacycontrol.enabled" to (settings?.globalPrivacyControlEnabled ?: true),
      "privacy.donottrackheader.enabled" to (settings?.doNotTrackEnabled ?: true),
      "network.http.referer.trimmingPolicy" to (if (settings?.strictReferrerPolicy != false) 2 else 0),
      "network.http.referer.XOriginPolicy" to (if (settings?.strictReferrerPolicy != false) 2 else 0),
      "network.http.referer.XOriginTrimmingPolicy" to (if (settings?.strictReferrerPolicy != false) 2 else 0),
      // Smooth Scrolling & Hardware Acceleration
      "general.smoothScroll" to true,
      "general.smoothScroll.lines" to true,
      "general.smoothScroll.pages" to true,
      "general.smoothScroll.scrollbars" to true,
      "general.smoothScroll.other" to true,
      "general.smoothScroll.msdPhysics.enabled" to true,
      "apz.overscroll.enabled" to true,
      "apz.allow_zooming" to true,
      "apz.touch_start_tolerance" to "0.05",
      "apz.velocity_relevance_time_ms" to 300,
      "apz.max_velocity_inches_per_ms" to "70.0",
      "apz.fling_friction" to "0.002",
      "layers.acceleration.force-enabled" to true,
      "layers.async-pan-zoom.enabled" to true,
      "layers.offmainthreadcomposition.enabled" to true,
      "gfx.webrender.all" to true,
      "gfx.webrender.compositor" to true,
      "layout.css.touch_action.enabled" to true,
      "layout.css.scroll-behavior.enabled" to true,
      "privacy.resistFingerprinting.reduceTimerPrecision.microseconds" to 16666,
    )
  }

  fun getShieldPreferences(
    settings: com.remmi.browser.storage.BrowserSettings? = null
  ): Map<String, Any> {
    val dohProvider = settings?.dnsProvider ?: com.remmi.browser.security.DnsProvider.CLOUDFLARE
    val isSystemDns = dohProvider == com.remmi.browser.security.DnsProvider.SYSTEM
    val trrMode = if (isSystemDns) 5 else 2
    val trrUri = if (isSystemDns) "" else dohProvider.dohUri

    return mapOf(
      "network.proxy.type" to 0, // Direct connection
      "network.proxy.socks" to "",
      "network.proxy.socks_port" to 0,
      "network.proxy.failover_direct" to true,
      "dom.security.https_only_mode" to (settings?.httpsOnlyMode ?: true),
      "network.dns.disablePrefetch" to true,
      "network.dns.disablePrefetchFromHTTPS" to true,
      "network.trr.mode" to trrMode, // Encrypted DNS (DoH) First
      "network.trr.uri" to trrUri,
      "network.dns.echconfig.enabled" to (settings?.encryptedClientHelloEnabled ?: true), // ECH (Encrypted Client Hello)
      "network.dns.use_https_rr_as_alpn" to true,
      "security.tls.version.min" to 3, // TLS 1.2 minimum
      "security.tls.version.max" to 4, // TLS 1.3 maximum
      "network.websocket.allowInsecureFromHTTPS" to false, // Block insecure WebSocket on HTTPS
      "security.mixed_content.block_active_content" to true,
      "security.mixed_content.upgrade_display_content" to true,
      "media.peerconnection.enabled" to !(settings?.blockWebRTC ?: true),
      "network.captive-portal-service.enabled" to false,
      "network.http.speculative-parallel-limit" to 2,
      "privacy.fingerprintingProtection" to (settings?.antiFingerprintingFPP ?: true),
      "privacy.globalprivacycontrol.enabled" to (settings?.globalPrivacyControlEnabled ?: true),
      "privacy.donottrackheader.enabled" to (settings?.doNotTrackEnabled ?: true),
      "network.http.referer.trimmingPolicy" to (if (settings?.strictReferrerPolicy != false) 2 else 0),
      "network.http.referer.XOriginPolicy" to (if (settings?.strictReferrerPolicy != false) 2 else 0),
      "network.http.referer.XOriginTrimmingPolicy" to (if (settings?.strictReferrerPolicy != false) 2 else 0),
      // Smooth Scrolling & Hardware Acceleration
      "general.smoothScroll" to true,
      "general.smoothScroll.lines" to true,
      "general.smoothScroll.pages" to true,
      "general.smoothScroll.scrollbars" to true,
      "general.smoothScroll.other" to true,
      "general.smoothScroll.msdPhysics.enabled" to true,
      "apz.overscroll.enabled" to true,
      "apz.allow_zooming" to true,
      "apz.touch_start_tolerance" to "0.05",
      "apz.velocity_relevance_time_ms" to 300,
      "apz.max_velocity_inches_per_ms" to "70.0",
      "apz.fling_friction" to "0.002",
      "layers.acceleration.force-enabled" to true,
      "layers.async-pan-zoom.enabled" to true,
      "layers.offmainthreadcomposition.enabled" to true,
      "gfx.webrender.all" to true,
      "gfx.webrender.compositor" to true,
      "layout.css.touch_action.enabled" to true,
      "layout.css.scroll-behavior.enabled" to true,
    )
  }

  suspend fun applyTorNetworkSettings(
    runtime: GeckoRuntime?,
    port: Int? = CurrentTorRoute.currentSocksPort,
    generation: Long = CurrentTorRoute.currentGeneration,
    settings: com.remmi.browser.storage.BrowserSettings? = null,
  ): Boolean {
    if (runtime == null) {
      Log.w(TAG, "Cannot apply Tor network settings: Runtime is null")
      DebugLogManager.log("[ROUTE] NOT_READY profile=GHOST reason=no_runtime")
      return false
    }
    if (port == null || port <= 0) {
      Log.w(TAG, "Cannot apply Tor network settings: Invalid port $port")
      DebugLogManager.log("[ROUTE] NOT_READY profile=GHOST reason=no_port")
      return false
    }

    val targetKey = RouteKey(PrivacyProfile.GHOST, port, generation, System.identityHashCode(runtime))
    if (lastAppliedRouteKey == targetKey) {
      // Idempotent: configuration already active
      return true
    }

    DebugLogManager.log("[ROUTE] APPLY_START profile=GHOST port=$port generation=$generation")
    Log.i(TAG, "Enforcing native Gecko Tor SOCKS5 on 127.0.0.1:$port (failover_direct=false, generation=$generation)")

    val prefs = getTorPreferences(port, settings)
    val prefController = GeckoPreferenceController(runtime)
    val applied = prefController.applyPreferences(prefs, GeckoPreferenceController.PREF_BRANCH_USER)

    if (applied) {
      val verifyKeys = listOf(
        "network.proxy.type",
        "network.proxy.socks",
        "network.proxy.socks_port",
        "network.proxy.socks_remote_dns",
        "network.proxy.failover_direct"
      )
      val readBack = prefController.getPreferences(verifyKeys)
      val isReadbackValid = readBack["network.proxy.type"] == 1 &&
        readBack["network.proxy.socks"] == "127.0.0.1" &&
        readBack["network.proxy.socks_port"] == port &&
        readBack["network.proxy.socks_remote_dns"] == true &&
        readBack["network.proxy.failover_direct"] == false
      
      if (!isReadbackValid) {
        Log.e(TAG, "Critical Ghost preferences readback failed! Expected proxy on $port but got: $readBack")
        DebugLogManager.log("[ROUTE] gecko_proxy_failed profile=GHOST reason=readback_mismatch readback=$readBack")
        return false
      }

      DebugLogManager.log("[ROUTE] GEOCKO_PREF_READBACK_OK")
      lastAppliedRouteKey = targetKey
      DebugLogManager.log("[ROUTE] NATIVE_GECKO_APPLIED profile=GHOST port=$port")
    } else {
      DebugLogManager.log("[ROUTE] gecko_proxy_failed profile=GHOST port=$port")
    }
    return applied
  }

  suspend fun applyShieldNetworkSettings(
    runtime: GeckoRuntime?,
    generation: Long = CurrentTorRoute.currentGeneration,
    settings: com.remmi.browser.storage.BrowserSettings? = null,
  ): Boolean {
    if (runtime == null) return false
    val targetKey = RouteKey(PrivacyProfile.SHIELD, 0, generation, System.identityHashCode(runtime))
    if (lastAppliedRouteKey == targetKey) {
      return true
    }

    DebugLogManager.log("[ROUTE] APPLY_START profile=SHIELD generation=$generation")
    Log.i(TAG, "Restoring native Gecko direct clearnet routing (WebRTC=disabled, generation=$generation)")

    val prefs = getShieldPreferences(settings)
    val prefController = GeckoPreferenceController(runtime)
    val applied = prefController.applyPreferences(prefs, GeckoPreferenceController.PREF_BRANCH_USER)

    if (applied) {
      DebugLogManager.log("[ROUTE] GEOCKO_PREF_READBACK_OK")
      lastAppliedRouteKey = targetKey
      DebugLogManager.log("[ROUTE] NATIVE_GECKO_APPLIED profile=SHIELD")
    } else {
      DebugLogManager.log("[ROUTE] gecko_proxy_failed profile=SHIELD")
    }
    return applied
  }

  fun resetAppliedState() {
    lastAppliedRouteKey = null
  }

  fun sanitizeUrl(rawUrl: String): String {
    var trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return "about:blank"

    // If it's a domain/search query
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://") && !trimmed.startsWith("about:") && !trimmed.startsWith("file:")) {
      if (trimmed.contains(".") && !trimmed.contains(" ")) {
        // Enforce HTTPS
        trimmed = "https://$trimmed"
      } else {
        // Privacy search via DuckDuckGo onion or clearnet privacy search
        val query = java.net.URLEncoder.encode(trimmed, "UTF-8")
        trimmed = "https://duckduckgo.com/?q=$query&t=remmi&kae=d"
      }
    }

    // Always upgrade http to https unless .onion or internal
    if (trimmed.startsWith("http://") && !NetworkRouteAuthority.isOnionDestination(trimmed)) {
      trimmed = trimmed.replaceFirst("http://", "https://")
    }

    return trimmed
  }
}

