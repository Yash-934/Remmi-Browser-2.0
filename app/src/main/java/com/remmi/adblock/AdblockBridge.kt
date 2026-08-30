package com.remmi.adblock

import android.util.Log
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Remmi Adblock Bridge
 * Bridges to native Rust adblock engine (libadblock_rust.so) with deterministic fallback to built-in rules.
 */
class AdblockBridge {

  private val blockedHostnames = ConcurrentHashMap.newKeySet<String>()
  private val blockedSubstrings = CopyOnWriteArrayList<String>()
  private val allowList = ConcurrentHashMap.newKeySet<String>()

  val totalBlockedCount = AtomicInteger(0)
  var isNativeLoaded: Boolean = false
    private set

  init {
    initEngine()
  }

  private fun initEngine() {
    try {
      System.loadLibrary("adblock_rust")
      val initSuccess = nativeInit()
      if (initSuccess) {
        isNativeLoaded = true
        Log.i(TAG, "Native adblock_rust loaded and initialized successfully!")
      } else {
        isNativeLoaded = false
        Log.w(TAG, "Native adblock_rust library loaded but nativeInit returned false. Using Kotlin fallback engine.")
      }
    } catch (e: UnsatisfiedLinkError) {
      Log.w(TAG, "libadblock_rust.so not found or signature mismatch. Using Kotlin fallback engine.", e)
      isNativeLoaded = false
    } catch (e: Throwable) {
      Log.w(TAG, "Failed initializing native adblock engine, falling back to Kotlin engine", e)
      isNativeLoaded = false
    }

    loadDefaultTrackerRules()

    if (isNativeLoaded) {
      selfTest()
    }
  }

  fun selfTest(): Boolean {
    if (!isNativeLoaded) {
      Log.d(TAG, "[ADBLOCK_SELF_TEST] native not loaded (using Kotlin fallback)")
      return false
    }

    return try {
      val result = nativeMatches(
        "https://example.com/test.js",
        "https://example.com/",
        "script"
      )
      Log.d(TAG, "[ADBLOCK_SELF_TEST] result=$result")
      true
    } catch (t: Throwable) {
      Log.e(TAG, "[ADBLOCK_SELF_TEST] failed", t)
      false
    }
  }

  fun loadDefaultTrackerRules() {
    val defaultDomains = listOf(
      "doubleclick.net", "googlesyndication.com", "google-analytics.com",
      "googletagmanager.com", "adservice.google.com", "admob.com",
      "adnxs.com", "adsrvr.org", "criteo.com", "criteo.net",
      "outbrain.com", "taboola.com", "scorecardresearch.com",
      "quantserve.com", "quantcount.com", "moatads.com",
      "pubmatic.com", "rubiconproject.com", "openx.net",
      "casalemedia.com", "applovin.com", "unityads.unity3d.com",
      "vungle.com", "appsflyer.com", "branch.io", "adjust.com",
      "kochava.com", "singular.net", "facebook.net/tr",
      "connect.facebook.net", "ads-twitter.com", "analytics.twitter.com",
      "bat.bing.com", "clarity.ms", "hotjar.com", "mouseflow.com",
      "segment.io", "segment.com", "mixpanel.com", "amplitude.com",
      "newrelic.com", "optimizely.com", "smartadserver.com",
      "yieldmo.com", "indexww.com", "chartbeat.com", "adroll.com",
      "advertising.com", "amazon-adsystem.com", "bidswitch.net",
      "revcontent.com", "mgid.com", "zergnet.com", "popads.net"
    )
    blockedHostnames.addAll(defaultDomains)

    val defaultPatterns = listOf(
      "/ads/", "/ad-banner", "/advertisement", "/trackers/",
      "pixel.gif", "beacon.js", "analytics.js", "gtag/js",
      "pagead2.googlesyndication.com", "adserver.", "adsystem.",
      "telemetry.", "tracking.", "statcounter.com"
    )
    blockedSubstrings.addAll(defaultPatterns)

    if (isNativeLoaded) {
      val rulesText = defaultDomains.joinToString("\n") { "||$it^" } + "\n" +
        defaultPatterns.joinToString("\n")
      try {
        nativeCompileRules(rulesText)
      } catch (e: Throwable) {
        Log.e(TAG, "Failed to compile default rules into native engine", e)
      }
    }
  }

  fun addCustomRule(rule: String) {
    val trimmed = rule.trim()
    if (trimmed.startsWith("@@")) {
      allowList.add(trimmed.removePrefix("@@").removePrefix("||").removeSuffix("^"))
    } else if (trimmed.startsWith("||")) {
      blockedHostnames.add(trimmed.removePrefix("||").removeSuffix("^"))
    } else {
      blockedSubstrings.add(trimmed)
    }
  }

  fun compileRules(rulesText: String): Int {
    var compiledCount = 0
    if (isNativeLoaded) {
      try {
        compiledCount = nativeCompileRules(rulesText)
      } catch (e: Throwable) {
        Log.e(TAG, "Native compile rules failed: ${e.message}", e)
      }
    }
    blockedHostnames.clear()
    blockedSubstrings.clear()
    allowList.clear()

    // Re-seed default tracker domains in Kotlin fallback
    val defaultDomains = listOf(
      "doubleclick.net", "googlesyndication.com", "google-analytics.com",
      "googletagmanager.com", "adservice.google.com", "admob.com",
      "adnxs.com", "adsrvr.org", "criteo.com", "criteo.net",
      "outbrain.com", "taboola.com", "scorecardresearch.com",
      "quantserve.com", "quantcount.com", "moatads.com",
      "pubmatic.com", "rubiconproject.com", "openx.net",
      "casalemedia.com", "applovin.com", "unityads.unity3d.com",
      "vungle.com", "appsflyer.com", "branch.io", "adjust.com",
      "kochava.com", "singular.net", "facebook.net/tr",
      "connect.facebook.net", "ads-twitter.com", "analytics.twitter.com",
      "bat.bing.com", "clarity.ms", "hotjar.com", "mouseflow.com",
      "segment.io", "segment.com", "mixpanel.com", "amplitude.com",
      "newrelic.com", "optimizely.com", "smartadserver.com",
      "yieldmo.com", "indexww.com", "chartbeat.com", "adroll.com",
      "advertising.com", "amazon-adsystem.com", "bidswitch.net",
      "revcontent.com", "mgid.com", "zergnet.com", "popads.net"
    )
    blockedHostnames.addAll(defaultDomains)

    val defaultPatterns = listOf(
      "/ads/", "/ad-banner", "/advertisement", "/trackers/",
      "pixel.gif", "beacon.js", "analytics.js", "gtag/js",
      "pagead2.googlesyndication.com", "adserver.", "adsystem.",
      "telemetry.", "tracking.", "statcounter.com"
    )
    blockedSubstrings.addAll(defaultPatterns)

    // Also parse into Kotlin memory fallback
    rulesText.lines().forEach { line ->
      val trimmed = line.trim()
      if (trimmed.isNotEmpty() && !trimmed.startsWith("!")) {
        if (trimmed.startsWith("@@")) {
          allowList.add(trimmed.removePrefix("@@").removePrefix("||").removeSuffix("^"))
        } else if (trimmed.startsWith("||")) {
          blockedHostnames.add(trimmed.removePrefix("||").removeSuffix("^"))
        } else {
          blockedSubstrings.add(trimmed)
        }
        if (!isNativeLoaded) compiledCount++
      }
    }
    return compiledCount
  }

  fun shouldBlock(url: String, sourceUrl: String = "", resourceType: String = "other"): Boolean {
    val startNs = System.nanoTime()
    try {
      if (isNativeLoaded) {
        try {
          val blocked = nativeMatches(url, sourceUrl, resourceType)
          if (blocked) {
            totalBlockedCount.incrementAndGet()
            logSlowDecisionIfNeeded(startNs, resourceType)
            return true
          }
        } catch (e: Throwable) {
          Log.e(TAG, "[ADBLOCK_NATIVE_MATCH_ERROR] nativeMatches failed for url=$url type=$resourceType", e)
          throw e
        }
      }

      val uri = URI(url)
      val host = uri.host?.lowercase() ?: run {
        logSlowDecisionIfNeeded(startNs, resourceType)
        return false
      }

      if (allowList.any { rule -> host == rule || host.endsWith(".$rule") }) {
        logSlowDecisionIfNeeded(startNs, resourceType)
        return false
      }

      for (blockedHost in blockedHostnames) {
        if (host == blockedHost || host.endsWith(".$blockedHost")) {
          totalBlockedCount.incrementAndGet()
          logSlowDecisionIfNeeded(startNs, resourceType)
          return true
        }
      }

      val lowerUrl = url.lowercase()
      for (pattern in blockedSubstrings) {
        if (lowerUrl.contains(pattern)) {
          totalBlockedCount.incrementAndGet()
          logSlowDecisionIfNeeded(startNs, resourceType)
          return true
        }
      }

      logSlowDecisionIfNeeded(startNs, resourceType)
      return false
    } catch (e: Exception) {
      Log.e(TAG, "[ADBLOCK_MATCH_ERROR] shouldBlock failed for url=$url", e)
      throw e
    }
  }

  private fun logSlowDecisionIfNeeded(startNs: Long, resourceType: String) {
    val elapsedUs = (System.nanoTime() - startNs) / 1_000
    if (elapsedUs > 10_000) {
      Log.w(TAG, "Slow adblock decision: ${elapsedUs}us type=$resourceType")
    }
  }

  fun getLoadedRulesCount(): Int {
    if (isNativeLoaded) {
      try {
        val count = nativeGetFilterCount()
        if (count > 0) return count
      } catch (_: Throwable) {}
    }
    return blockedHostnames.size + blockedSubstrings.size
  }

  // Native JNI functions implemented in rust/src/lib.rs
  private external fun nativeInit(): Boolean
  private external fun nativeMatches(url: String, sourceUrl: String, requestType: String): Boolean
  private external fun nativeCompileRules(rulesText: String): Int
  private external fun nativeGetFilterCount(): Int
  private external fun nativeGetBlockedCount(): Int

  companion object {
    private const val TAG = "AdblockBridge"

    @Volatile
    private var INSTANCE: AdblockBridge? = null

    fun getInstance(): AdblockBridge {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: AdblockBridge().also { INSTANCE = it }
      }
    }
  }
}
