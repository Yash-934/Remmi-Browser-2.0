package com.remmi.adblock

import android.util.Log
import kotlinx.coroutines.launch
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class AdblockState {
  STARTING,
  READY,
  DEGRADED,
  FAILED
}

data class BlockDecision(
  val blocked: Boolean,
  val ruleId: String? = null,
  val ruleSource: String? = null,
  val engineGeneration: Long = 0L,
  val redirectUrl: String? = null,
  val rewrittenUrl: String? = null,
  val csp: String? = null,
  
  // Expose diagnostic match fields
  val defaultMatched: Boolean = false,
  val defaultException: Boolean = false,
  val defaultImportant: Boolean = false,
  val additionalMatched: Boolean = false,
  val additionalException: Boolean = false,
  val additionalImportant: Boolean = false,
)

data class NetworkRequestContext(
  val url: String,
  val requestInitiator: String,
  val resourceType: String,
  val method: String,
  val aggressive: Boolean,
  val thirdParty: Boolean,
  
  val previouslyMatchedRule: Boolean = false,
  val previouslyMatchedException: Boolean = false,
  val previouslyMatchedImportant: Boolean = false
)

data class NativeMatchResult(
  val blocked: Boolean,
  val redirect: String?,
  val rewrittenUrl: String?,
  val csp: String?,
  val defaultMatched: Boolean,
  val defaultException: Boolean,
  val defaultImportant: Boolean,
  val additionalMatched: Boolean,
  val additionalException: Boolean,
  val additionalImportant: Boolean
)

data class CosmeticResources(
  val ok: Boolean,
  val generation: Long,
  val hideSelectors: List<String> = emptyList(),
  val forceHideSelectors: List<String> = emptyList(),
  val procedural: List<String> = emptyList(),
  val proceduralCount: Int = 0,
  val generics: Boolean = true,
  val error: String? = null
)

data class FallbackNetworkRule(
  val raw: String,
  val isException: Boolean,
  val isImportant: Boolean,
  val domainPattern: String?,
  val substringPattern: String?,
  val resourceTypes: Set<String> = emptySet(),
  val excludedResourceTypes: Set<String> = emptySet(),
  val methods: Set<String> = emptySet(),
  val thirdParty: Boolean? = null
) {
  fun matches(
    targetUrl: String,
    host: String,
    reqMethod: String,
    reqResourceType: String,
    isThirdParty: Boolean
  ): Boolean {
    if (methods.isNotEmpty() && !methods.contains(reqMethod.uppercase())) {
      return false
    }
    if (thirdParty != null && thirdParty != isThirdParty) {
      return false
    }
    val normType = reqResourceType.lowercase().trim()
    if (resourceTypes.isNotEmpty() && !resourceTypes.any { matchesType(it, normType) }) {
      return false
    }
    if (excludedResourceTypes.isNotEmpty() && excludedResourceTypes.any { matchesType(it, normType) }) {
      return false
    }
    if (domainPattern != null) {
      val d = domainPattern.lowercase()
      if (d.contains('/')) {
        val urlNoScheme = targetUrl.lowercase().substringAfter("://")
        if (!urlNoScheme.startsWith(d) && !urlNoScheme.contains(".$d") && !urlNoScheme.contains("/$d")) {
          return false
        }
      } else {
        if (host != d && !host.endsWith(".$d")) {
          return false
        }
      }
    } else if (substringPattern != null) {
      if (!targetUrl.lowercase().contains(substringPattern.lowercase())) {
        return false
      }
    }
    return true
  }

  private fun matchesType(filterType: String, actualType: String): Boolean {
    val f = filterType.lowercase().trim()
    val a = actualType.lowercase().trim()
    if (f == a) return true
    if ((f == "xmlhttprequest" || f == "xhr") && (a == "xmlhttprequest" || a == "xhr" || a == "fetch")) return true
    if ((f == "beacon" || f == "ping") && (a == "beacon" || a == "ping")) return true
    if ((f == "csp_report" || f == "csp") && (a == "csp_report" || a == "csp")) return true
    if (f == "image" && (a == "image" || a == "imageset")) return true
    if (f == "subdocument" && (a == "subdocument" || a == "sub_frame")) return true
    if (f == "document" && (a == "document" || a == "main_frame")) return true
    return false
  }
}

/**
 * Remmi Adblock Bridge
 * Bridges to native Rust adblock engine (libadblock_rust.so) with deterministic fallback to built-in rules.
 */
class AdblockBridge {

  private val blockedHostnames = ConcurrentHashMap.newKeySet<String>()
  private val blockedSubstrings = CopyOnWriteArrayList<String>()
  private val allowList = ConcurrentHashMap.newKeySet<String>()
  private val fallbackNetworkRules = java.util.concurrent.CopyOnWriteArrayList<FallbackNetworkRule>()
  private val fallbackCosmeticRules = java.util.concurrent.CopyOnWriteArrayList<Pair<String?, String>>()
  private val fallbackAdditionalCosmeticRules = java.util.concurrent.CopyOnWriteArrayList<Pair<String?, String>>()
  private val fallbackProceduralFilters = java.util.concurrent.CopyOnWriteArrayList<String>() // domain (or null for generic) to selector
  private val fallbackCosmeticExceptions = ConcurrentHashMap.newKeySet<String>() // domain##selector or ##selector exception

  val totalBlockedCount = AtomicInteger(0)
  private val localEngineGeneration = AtomicLong(1L)

  var isNativeLoaded: Boolean = false
    private set

  var nativeBuildId: String = "unknown"
    private set

  var nativeAbi: String = "unknown"
    private set

  var nativeApiVersion: String = "unknown"
    private set

  var nativeNumericApiVersion: Int = 0
    private set

  var isNativeHiddenClassIdCompatible: Boolean = false
    private set

  var isJniSignatureCompatible: Boolean = false
    private set

  var state: AdblockState = AdblockState.STARTING
    private set

  private val initialized = AtomicBoolean(false)
  private val isInitializing = AtomicBoolean(false)

  init {
    // Lightweight constructor: initialize in-memory fallback rules only
    loadDefaultTrackerRules(compileToNative = false)
  }

  fun isNativeAvailable(): Boolean = isNativeLoaded

  fun verifyNativeCompatibility(apiVersion: Int): Boolean {
    // Version 2 corresponds to 3-argument nativeGetHiddenClassIdSelectors(classes, ids, exceptions).
    return apiVersion >= 2
  }

  fun verifyNativeCompatibility(version: String, buildId: String, abi: String): Boolean {
    if (version.startsWith("adblock-rust-0.8.0")) {
      return buildId.contains("v2-compat")
    }
    return (version.startsWith("adblock-rust-0.8.1") ||
            version.startsWith("adblock-rust-0.8.2") ||
            version.startsWith("adblock-rust-0.9") ||
            version.startsWith("adblock-rust-1.") ||
            buildId.contains("v2-compat"))
  }

  private fun logNativeCompatDiagnostic(compatible: Boolean) {
    Log.i(TAG, "[ADBLOCK_NATIVE_COMPAT]")
    Log.i(TAG, "compatible=$compatible")
    Log.i(TAG, "buildId=$nativeBuildId")
    Log.i(TAG, "abi=$nativeAbi")
    Log.i(TAG, "apiVersion=$nativeApiVersion")
    Log.i(TAG, "numericApiVersion=$nativeNumericApiVersion")
  }

  fun getEngineGeneration(): Long {
    if (isNativeLoaded) {
      try {
        val gen = nativeGetGeneration()
        if (gen > 0) return gen
      } catch (_: Throwable) {}
    }
    return localEngineGeneration.get()
  }

  fun initEngine() {
    if (initialized.get()) return
    if (!isInitializing.compareAndSet(false, true)) return

    com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(phase = com.remmi.browser.util.StartupPhase.ADBLOCK_CONSTRUCTION_START)
    try {
      System.loadLibrary("adblock_rust")
      com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(phase = com.remmi.browser.util.StartupPhase.ADBLOCK_NATIVE_LOAD_OK)
      
      com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_NATIVE_INIT_START]")
      val initSuccess = nativeInit()
      com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = if (initSuccess) "[ADBLOCK_NATIVE_INIT_OK]" else "[ADBLOCK_NATIVE_INIT_FAILED]")
      
      if (initSuccess) {
        com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(phase = com.remmi.browser.util.StartupPhase.ADBLOCK_NATIVE_INIT_OK)
        isNativeLoaded = true
        state = AdblockState.READY
        Log.i(TAG, "Native adblock_rust loaded and initialized successfully!")

        // Gate and query getters individually with persistent markers
        try {
          com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_VERSION_START]")
          nativeApiVersion = nativeGetVersion()
          com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_VERSION_OK]", apiVersion = nativeApiVersion)
        } catch (_: Throwable) {
          nativeApiVersion = "unknown"
          com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_VERSION_FAILED]")
        }

        try {
          com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_BUILDID_START]")
          nativeBuildId = nativeGetBuildId()
          com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_BUILDID_OK]", buildId = nativeBuildId)
        } catch (_: Throwable) {
          nativeBuildId = "unknown"
          com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_BUILDID_FAILED]")
        }

        try {
          com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_ABI_START]")
          nativeAbi = nativeGetAbi()
          com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_ABI_OK]", abi = nativeAbi)
        } catch (_: Throwable) {
          nativeAbi = "unknown"
          com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_ABI_FAILED]")
        }

        try {
          com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_APIVERSION_START]")
          nativeNumericApiVersion = nativeGetApiVersion()
          com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_APIVERSION_OK]")
        } catch (_: Throwable) {
          // If nativeGetApiVersion JNI symbol is not present in binary export table,
          // capability MUST be reported as UNKNOWN (0), NEVER assumed to be API v2.
          nativeNumericApiVersion = 0
          com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_APIVERSION_UNKNOWN]")
        }

        // Gate using explicit numeric API version: version >= 2 corresponds to proven 3-argument nativeGetHiddenClassIdSelectors
        isJniSignatureCompatible = (nativeNumericApiVersion >= 2)
        isNativeHiddenClassIdCompatible = isJniSignatureCompatible

        logNativeCompatDiagnostic(isJniSignatureCompatible)
      } else {
        isNativeLoaded = false
        state = AdblockState.DEGRADED
        Log.w(TAG, "Native adblock_rust library loaded but nativeInit returned false. Using Kotlin fallback engine.")
        logNativeCompatDiagnostic(false)
      }
    } catch (e: UnsatisfiedLinkError) {
      Log.w(TAG, "libadblock_rust.so not found or signature mismatch. Using Kotlin fallback engine.", e)
      isNativeLoaded = false
      state = AdblockState.DEGRADED
      logNativeCompatDiagnostic(false)
    } catch (e: Throwable) {
      Log.w(TAG, "Failed initializing native adblock engine, falling back to Kotlin engine", e)
      isNativeLoaded = false
      state = AdblockState.DEGRADED
      logNativeCompatDiagnostic(false)
    }

    loadDefaultTrackerRules(compileToNative = isNativeLoaded)

    if (isNativeLoaded) {
      selfTest()
    }
    initialized.set(true)
    isInitializing.set(false)
    com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(phase = com.remmi.browser.util.StartupPhase.ADBLOCK_CONSTRUCTION_END)
  }

  fun initializeAsync(scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)) {
    if (initialized.get()) return
    scope.launch {
      initEngine()
    }
  }

  suspend fun initialize(): Boolean {
    if (initialized.get()) {
      return true
    }

    return try {
      Log.d(TAG, "[ADBLOCK_FILTER_LOAD_START]")
      initEngine()

      val totalRules = getLoadedRulesCount()
      Log.d(TAG, "[ADBLOCK_RULES] total=$totalRules")

      if (isNativeLoaded) {
        logNativeCompatDiagnostic(isJniSignatureCompatible)
        val testOk = selfTest()
        if (testOk) {
          state = AdblockState.READY
          Log.d(TAG, "[ADBLOCK_READY] native=true")
        } else {
          state = AdblockState.DEGRADED
          Log.w(TAG, "[ADBLOCK_READY] native=false (degraded)")
        }
      } else {
        state = AdblockState.DEGRADED
        Log.i(TAG, "[ADBLOCK_READY] native=false (fallback engine active)")
      }

      true
    } catch (t: Throwable) {
      state = AdblockState.FAILED
      Log.e(TAG, "[ADBLOCK_INIT_FAILED]", t)
      false
    }
  }

  fun selfTest(): Boolean {
    if (!isNativeLoaded) {
      Log.w(TAG, "[ADBLOCK_SELF_TEST] native_not_loaded (using Kotlin fallback engine)")
      return false
    }

    return try {
      com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_SELFTEST_START]")
      val ok = nativeSelfTest()
      com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = if (ok) "[ADBLOCK_SELFTEST_OK]" else "[ADBLOCK_SELFTEST_FAILED]")
      Log.d(TAG, "[ADBLOCK_SELF_TEST] native=true deterministic=$ok")
      if (!ok) {
        Log.e(TAG, "[ADBLOCK_SELF_TEST] deterministic_self_test_failed")
      }
      ok
    } catch (t: Throwable) {
      com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_SELFTEST_FAILED]")
      Log.e(TAG, "[ADBLOCK_SELF_TEST] native_failed", t)
      false
    }
  }

  fun getNativeVersion(): String {
    if (!isNativeLoaded) return "none"
    return try {
      nativeGetVersion()
    } catch (_: Throwable) {
      "unknown"
    }
  }

  fun loadDefaultTrackerRules(compileToNative: Boolean = true) {
    allowList.clear()
    fallbackCosmeticExceptions.clear()
    fallbackNetworkRules.clear()

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
    for (d in defaultDomains) {
      fallbackNetworkRules.add(
        FallbackNetworkRule(
          raw = "||$d^",
          isException = false,
          isImportant = false,
          domainPattern = d,
          substringPattern = null
        )
      )
    }

    val defaultPatterns = listOf(
      "/ads/", "/ad-banner", "/advertisement", "/trackers/",
      "pixel.gif", "beacon.js", "analytics.js", "gtag/js",
      "pagead2.googlesyndication.com", "adserver.", "adsystem.",
      "telemetry.", "tracking.", "statcounter.com"
    )
    blockedSubstrings.addAll(defaultPatterns)
    for (p in defaultPatterns) {
      fallbackNetworkRules.add(
        FallbackNetworkRule(
          raw = p,
          isException = false,
          isImportant = false,
          domainPattern = null,
          substringPattern = p
        )
      )
    }

    if (compileToNative && isNativeLoaded) {
      val rulesText = defaultDomains.joinToString("\n") { "||$it^" } + "\n" +
        defaultPatterns.joinToString("\n")
      try {
        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_DEFAULT_RULES_START]")
        val json = nativeCompileRules(rulesText, "")
        Log.d(TAG, "[ADBLOCK_METRICS] init_metrics: $json")
        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_DEFAULT_RULES_OK]")
      } catch (e: Throwable) {
        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_DEFAULT_RULES_FAILED]")
        Log.e(TAG, "Failed to compile default rules into native engine", e)
      }
    }
  }

  private fun parseNetworkRule(ruleLine: String): FallbackNetworkRule? {
    var line = ruleLine.trim()
    if (line.isEmpty() || line.startsWith("!") || line.contains("##") || line.contains("#@#") || line.contains("#$#")) return null

    val isException = line.startsWith("@@")
    if (isException) {
      line = line.removePrefix("@@").trim()
    }

    var isImportant = false
    val resourceTypes = mutableSetOf<String>()
    val excludedResourceTypes = mutableSetOf<String>()
    val methods = mutableSetOf<String>()
    var thirdParty: Boolean? = null

    var patternPart = line
    val dollarIdx = line.indexOf('$')
    if (dollarIdx != -1) {
      patternPart = line.substring(0, dollarIdx).trim()
      val optionsPart = line.substring(dollarIdx + 1).trim()
      val options = optionsPart.split(',')
      for (opt in options) {
        val trimmedOpt = opt.trim().lowercase()
        if (trimmedOpt.isEmpty()) continue
        if (trimmedOpt == "important") {
          isImportant = true
        } else if (trimmedOpt == "third-party" || trimmedOpt == "3p") {
          thirdParty = true
        } else if (trimmedOpt == "~third-party" || trimmedOpt == "~3p" || trimmedOpt == "1p" || trimmedOpt == "~3p") {
          thirdParty = false
        } else if (trimmedOpt.startsWith("method=")) {
          val m = trimmedOpt.removePrefix("method=").trim().uppercase()
          if (m.isNotEmpty()) methods.add(m)
        } else if (trimmedOpt.startsWith("~")) {
          excludedResourceTypes.add(trimmedOpt.removePrefix("~"))
        } else if (!trimmedOpt.contains("=")) {
          resourceTypes.add(trimmedOpt)
        }
      }
    }

    var domainPattern: String? = null
    var substringPattern: String? = null

    if (patternPart.startsWith("||")) {
      var d = patternPart.removePrefix("||")
      if (d.endsWith("^")) d = d.removeSuffix("^")
      domainPattern = d.trim().ifEmpty { null }
    } else if (patternPart.isNotEmpty()) {
      var s = patternPart
      if (s.endsWith("^")) s = s.removeSuffix("^")
      substringPattern = s.trim().ifEmpty { null }
    }

    if (domainPattern == null && substringPattern == null) return null

    return FallbackNetworkRule(
      raw = ruleLine.trim(),
      isException = isException,
      isImportant = isImportant,
      domainPattern = domainPattern,
      substringPattern = substringPattern,
      resourceTypes = resourceTypes,
      excludedResourceTypes = excludedResourceTypes,
      methods = methods,
      thirdParty = thirdParty
    )
  }

  fun addCustomRule(rule: String) {
    val trimmed = rule.trim()
    val parsed = parseNetworkRule(trimmed)
    if (parsed != null) {
      fallbackNetworkRules.add(0, parsed)
    }
    if (trimmed.startsWith("@@")) {
      val clean = trimmed.removePrefix("@@").removePrefix("||").removeSuffix("^").trim()
      if (clean.isNotEmpty()) allowList.add(clean)
    } else if (trimmed.startsWith("||")) {
      val clean = trimmed.removePrefix("||").removeSuffix("^").trim()
      if (clean.isNotEmpty()) blockedHostnames.add(clean)
    } else if (trimmed.isNotEmpty()) {
      blockedSubstrings.add(trimmed)
    }
  }

  fun compileRules(defaultRulesText: String, additionalRulesText: String = ""): Int {
    Log.d(TAG, "[ADBLOCK_FILTER_COMPILE_START] defaultLength=${defaultRulesText.length} additionalLength=${additionalRulesText.length}")
    
    val validLines = (defaultRulesText.lines() + additionalRulesText.lines()).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("!") }
    if (validLines.isEmpty()) {
      Log.d(TAG, "[ADBLOCK_COMPILE] empty or comment-only rulesText, preserving active engine")
      return 0
    }

    // Always preserve default tracker domains & patterns
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
    val defaultPatterns = listOf(
      "/ads/", "/ad-banner", "/advertisement", "/trackers/",
      "pixel.gif", "beacon.js", "analytics.js", "gtag/js",
      "pagead2.googlesyndication.com", "adserver.", "adsystem.",
      "telemetry.", "tracking.", "statcounter.com"
    )

    val builtinRulesText = defaultDomains.joinToString("\n") { "||$it^" } + "\n" +
      defaultPatterns.joinToString("\n")

    val combinedDefaultRulesText = if (defaultRulesText.isNotBlank()) {
      "$builtinRulesText\n$defaultRulesText"
    } else {
      builtinRulesText
    }

    var compiledCount = 0
    val oldGen = getEngineGeneration()
    if (isNativeLoaded) {
      try {
        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_COMPILE_RULES_START]")
        val metricsJson = nativeCompileRules(combinedDefaultRulesText, additionalRulesText)
        val metricsObj = org.json.JSONObject(metricsJson)
        compiledCount = metricsObj.optInt("parsedCandidates", 0)
        Log.i(TAG, "[ADBLOCK_METRICS] compile_metrics: $metricsJson")
        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_COMPILE_RULES_OK]")
      } catch (e: Throwable) {
        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_COMPILE_RULES_FAILED]")
        Log.e(TAG, "Native compile rules failed: ${e.message}", e)
      }
    }
    val newGen = localEngineGeneration.incrementAndGet()
    Log.d(TAG, "[ADBLOCK_ENGINE_SWAP] oldGeneration=$oldGen newGeneration=$newGen rules=$compiledCount")

    blockedHostnames.clear()
    blockedSubstrings.clear()
    allowList.clear()
    fallbackCosmeticRules.clear()
    fallbackCosmeticExceptions.clear()
    fallbackNetworkRules.clear()

    // Re-seed default tracker domains in Kotlin fallback
    blockedHostnames.addAll(defaultDomains)
    for (d in defaultDomains) {
      fallbackNetworkRules.add(
        FallbackNetworkRule(
          raw = "||$d^",
          isException = false,
          isImportant = false,
          domainPattern = d,
          substringPattern = null
        )
      )
    }
    blockedSubstrings.addAll(defaultPatterns)
    for (p in defaultPatterns) {
      fallbackNetworkRules.add(
        FallbackNetworkRule(
          raw = p,
          isException = false,
          isImportant = false,
          domainPattern = null,
          substringPattern = p
        )
      )
    }

    // Also parse into Kotlin memory fallback
    fallbackAdditionalCosmeticRules.clear()
    fallbackProceduralFilters.clear()
    
    fun parseToFallback(rules: String, isAdditional: Boolean) {
      if (rules.isBlank()) return
      rules.lines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("!")) {
          if (trimmed.contains("#@#")) {
            fallbackCosmeticExceptions.add(trimmed)
          } else if (trimmed.contains("#$#")) {
            val parts = trimmed.split("#$#", limit = 2)
            if (parts.size == 2 && parts[1].isNotBlank()) {
              fallbackProceduralFilters.add(parts[1].trim())
            }
          } else if (trimmed.contains("##")) {
            val parts = trimmed.split("##", limit = 2)
            val domain = parts[0].trim().ifEmpty { null }
            val selector = parts[1].trim()
            if (selector.isNotEmpty()) {
              if (isAdditional) fallbackAdditionalCosmeticRules.add(Pair(domain, selector))
              else fallbackCosmeticRules.add(Pair(domain, selector))
            }
          } else {
            val parsedNet = parseNetworkRule(trimmed)
            if (parsedNet != null) {
              fallbackNetworkRules.add(0, parsedNet)
            }
            if (trimmed.startsWith("@@")) {
              val clean = trimmed.removePrefix("@@").removePrefix("||").removeSuffix("^").trim()
              if (clean.isNotEmpty()) allowList.add(clean)
            } else if (trimmed.startsWith("||")) {
              val clean = trimmed.removePrefix("||").removeSuffix("^").trim()
              if (clean.isNotEmpty()) blockedHostnames.add(clean)
            } else {
              blockedSubstrings.add(trimmed)
            }
          }
          if (!isNativeLoaded) compiledCount++
        }
      }
    }
    
    parseToFallback(combinedDefaultRulesText, false)
    parseToFallback(additionalRulesText, true)
    Log.d(TAG, "[ADBLOCK_FILTER_COMPILE_DONE] compiled=$compiledCount total=${getLoadedRulesCount()}")
    return compiledCount
  }

  fun getCosmeticResources(
    url: String,
    classes: List<String> = emptyList(),
    ids: List<String> = emptyList(),
    exceptions: List<String> = emptyList(),
    aggressive: Boolean = false
  ): CosmeticResources {
    val currentGen = getEngineGeneration()
    if (isNativeLoaded) {
      try {
        val classesJson = org.json.JSONArray(classes).toString()
        val idsJson = org.json.JSONArray(ids).toString()
        val exceptionsJson = org.json.JSONArray(exceptions).toString()
        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_COSMETIC_START]")
        val resultJson = nativeGetCosmeticResources(url, classesJson, idsJson, exceptionsJson, aggressive)
        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_COSMETIC_OK]")
        if (resultJson.isNotBlank()) {
          val obj = org.json.JSONObject(resultJson)
          val ok = obj.optBoolean("ok", true)
          val gen = obj.optLong("generation", currentGen)
          val hideArray = obj.optJSONArray("hideSelectors")
          val hideList = mutableListOf<String>()
          if (hideArray != null) {
            for (i in 0 until hideArray.length()) {
              hideList.add(hideArray.getString(i))
            }
          }
          val forceArray = obj.optJSONArray("forceHideSelectors")
          val forceList = mutableListOf<String>()
          if (forceArray != null) {
            for (i in 0 until forceArray.length()) {
              forceList.add(forceArray.getString(i))
            }
          }
          val procArray = obj.optJSONArray("procedural")
          val procList = mutableListOf<String>()
          if (procArray != null) {
            for (i in 0 until procArray.length()) {
              procList.add(procArray.getString(i))
            }
          }
          val procCount = obj.optInt("proceduralCount", procList.size)
          val generics = obj.optBoolean("generics", true)
          val err = if (obj.has("error")) obj.getString("error") else null

          return CosmeticResources(
            ok = ok,
            generation = gen,
            hideSelectors = hideList,
            forceHideSelectors = forceList,
            procedural = procList,
            proceduralCount = procCount,
            generics = generics,
            error = err
          )
        }
      } catch (t: Throwable) {
        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_COSMETIC_FAILED]")
        Log.e(TAG, "[COSMETIC_ERROR] native cosmetic lookup error: ${t.message}", t)
      }
    }

    // Kotlin Fallback Engine
    val host = try {
      val uri = URI(url)
      uri.host?.lowercase() ?: ""
    } catch (_: Exception) { "" }

    val hideList = mutableListOf<String>()
    val forceHideList = mutableListOf<String>()
    
    fun matchRules(rules: List<Pair<String?, String>>, targetList: MutableList<String>) {
      for ((domain, selector) in rules) {
        if (domain == null) {
          targetList.add(selector)
        } else {
          val domains = domain.split(",")
          val matches = domains.any { d ->
            val cleanD = d.trim().lowercase()
            cleanD.isNotEmpty() && (host == cleanD || host.endsWith(".$cleanD"))
          }
          val isExcluded = domains.any { d ->
            val cleanD = d.trim().lowercase()
            cleanD.startsWith("~") && (host == cleanD.substring(1) || host.endsWith(".${cleanD.substring(1)}"))
          }
          if (matches && !isExcluded) {
            targetList.add(selector)
          }
        }
      }
    }
    
    matchRules(fallbackCosmeticRules, hideList)
    matchRules(fallbackAdditionalCosmeticRules, forceHideList)

    // Apply exceptions
    for (ex in fallbackCosmeticExceptions) {
      val parts = ex.split("#@#", limit = 2)
      if (parts.size == 2) {
        val exDomain = parts[0].trim().lowercase()
        val exSelector = parts[1].trim()
        if (exDomain.isEmpty() || host == exDomain || host.endsWith(".$exDomain")) {
          hideList.remove(exSelector)
          forceHideList.remove(exSelector)
        }
      }
    }
    
    // Also parse procedural filters manually for fallback
    val proceduralList = if (aggressive) fallbackProceduralFilters.toList() else emptyList()
    
    return CosmeticResources(
      ok = true,
      generation = currentGen,
      hideSelectors = hideList.distinct(),
      forceHideSelectors = forceHideList.distinct(),
      procedural = proceduralList,
      proceduralCount = proceduralList.size,
      generics = true,
      error = null
    )
  }

  fun getHiddenClassIdSelectors(
    classes: List<String>,
    ids: List<String>,
    exceptions: List<String> = emptyList()
  ): CosmeticResources {
    val currentGen = getEngineGeneration()
    // Gated: NEVER invoke nativeGetHiddenClassIdSelectors unless native binary is proven compatible (requires fresh .so rebuild)
    if (isNativeLoaded && isNativeHiddenClassIdCompatible) {
      try {
        val classesJson = org.json.JSONArray(classes).toString()
        val idsJson = org.json.JSONArray(ids).toString()
        val exceptionsJson = org.json.JSONArray(exceptions).toString()
        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_HIDDEN_SELECTORS_START]")
        val resultJson = nativeGetHiddenClassIdSelectors(classesJson, idsJson, exceptionsJson)
        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_HIDDEN_SELECTORS_OK]")
        if (resultJson.isNotBlank()) {
          val obj = org.json.JSONObject(resultJson)
          val ok = obj.optBoolean("ok", true)
          val gen = obj.optLong("generation", currentGen)
          val hideArray = obj.optJSONArray("hideSelectors")
          val hideList = mutableListOf<String>()
          if (hideArray != null) {
            for (i in 0 until hideArray.length()) {
              hideList.add(hideArray.getString(i))
            }
          }
          val forceArray = obj.optJSONArray("forceHideSelectors")
          val forceList = mutableListOf<String>()
          if (forceArray != null) {
            for (i in 0 until forceArray.length()) {
              forceList.add(forceArray.getString(i))
            }
          }
          val procArray = obj.optJSONArray("procedural")
          val procList = mutableListOf<String>()
          if (procArray != null) {
            for (i in 0 until procArray.length()) {
              procList.add(procArray.getString(i))
            }
          }
          val procCount = obj.optInt("proceduralCount", procList.size)
          val generics = obj.optBoolean("generics", true)
          val err = if (obj.has("error")) obj.getString("error") else null

          return CosmeticResources(
            ok = ok,
            generation = gen,
            hideSelectors = hideList,
            forceHideSelectors = forceList,
            procedural = procList,
            proceduralCount = procCount,
            generics = generics,
            error = err
          )
        }
      } catch (t: Throwable) {
        com.remmi.browser.util.CrashHandlerHelper.recordNativeOp(op = "[ADBLOCK_HIDDEN_SELECTORS_FAILED]")
        Log.e(TAG, "[COSMETIC_ERROR] native hidden class/id lookup error: ${t.message}", t)
      }
    }

    return CosmeticResources(
      ok = true,
      generation = currentGen,
      hideSelectors = emptyList(),
      forceHideSelectors = emptyList(),
      procedural = emptyList(),
      proceduralCount = 0,
      generics = true,
      error = null
    )
  }

  fun shouldBlock(url: String, sourceUrl: String = "", resourceType: String = "other"): Boolean {
    return evaluateDecision(url, sourceUrl, resourceType = resourceType).blocked
  }

  fun evaluateDecision(
    url: String, 
    sourceUrl: String = "", 
    initiator: String = "",
    method: String = "GET",
    resourceType: String = "other",
    aggressive: Boolean = false,
    thirdParty: Boolean = true,
    requestId: String = "n/a"
  ): BlockDecision {
    val startNs = System.nanoTime()
    val isTraceCandidate = url.contains("google-analytics") || url.contains("adblock-tester") || url.contains("googletagmanager") || url.contains("banner")
    if (isTraceCandidate && requestId != "n/a") {
      Log.d(TAG, "[NATIVE_MATCH_START] requestId=$requestId url=${url.take(60)}")
    }
    val currentGen = getEngineGeneration()
    try {
      if (isNativeLoaded) {
        try {
          // Serialize request context
          val context = org.json.JSONObject().apply {
            put("url", url)
            put("requestInitiator", initiator)
            put("sourceUrl", sourceUrl)
            put("resourceType", resourceType)
            put("method", method)
            put("aggressive", aggressive)
            put("thirdParty", thirdParty)
          }.toString()

          val resultJson = nativeMatchesJson(context)
          val resultObj = org.json.JSONObject(resultJson)
          val blocked = resultObj.optBoolean("blocked", false)
          
          if (blocked) {
            totalBlockedCount.incrementAndGet()
          }
          logSlowDecisionIfNeeded(startNs, resourceType)
          
          val elapsedNs = System.nanoTime() - startNs
          if (isTraceCandidate && requestId != "n/a") {
            Log.d(TAG, "[NATIVE_MATCH_END] requestId=$requestId elapsedNanos=$elapsedNs blocked=$blocked")
          }

          return BlockDecision(
            blocked = blocked,
            ruleId = "native",
            ruleSource = "RustEngine",
            engineGeneration = currentGen,
            redirectUrl = if (resultObj.has("redirect") && !resultObj.isNull("redirect")) resultObj.optString("redirect").takeIf { it.isNotEmpty() } else null,
            rewrittenUrl = if (resultObj.has("rewrittenUrl") && !resultObj.isNull("rewrittenUrl")) resultObj.optString("rewrittenUrl").takeIf { it.isNotEmpty() } else null,
            csp = if (resultObj.has("csp") && !resultObj.isNull("csp")) resultObj.optString("csp").takeIf { it.isNotEmpty() } else null,
            defaultMatched = resultObj.optBoolean("defaultMatched", false),
            defaultException = resultObj.optBoolean("defaultException", false),
            defaultImportant = resultObj.optBoolean("defaultImportant", false),
            additionalMatched = resultObj.optBoolean("additionalMatched", false),
            additionalException = resultObj.optBoolean("additionalException", false),
            additionalImportant = resultObj.optBoolean("additionalImportant", false)
          )
        } catch (t: Throwable) {
          state = AdblockState.DEGRADED
          Log.e(TAG, "[ADBLOCK_DECISION_ERROR] ${t.javaClass.name}: ${t.message}", t)
          // Fall through to Kotlin fallback on error
        }
      }

      val uri = try {
        URI(url)
      } catch (e: Exception) {
        Log.e(TAG, "[ADBLOCK_DECISION_ERROR] invalid_url: ${url.take(30)}...", e)
        throw e
      }

      val host = uri.host?.lowercase() ?: run {
        logSlowDecisionIfNeeded(startNs, resourceType)
        return BlockDecision(
          blocked = false,
          ruleId = "invalid_host",
          ruleSource = "KotlinFallback",
          engineGeneration = currentGen
        )
      }

      val lowerUrl = url.lowercase()

      // 1. Check Important Exceptions (@@...$important)
      val importantException = fallbackNetworkRules.firstOrNull { it.isException && it.isImportant && it.matches(lowerUrl, host, method, resourceType, thirdParty) }
      if (importantException != null) {
        logSlowDecisionIfNeeded(startNs, resourceType)
        return BlockDecision(
          blocked = false,
          ruleId = "important_exception:${importantException.raw}",
          ruleSource = "KotlinFallback",
          engineGeneration = currentGen,
          defaultException = true,
          defaultImportant = true
        )
      }

      // 2. Check Important Blocks (...$important)
      val importantBlock = fallbackNetworkRules.firstOrNull { !it.isException && it.isImportant && it.matches(lowerUrl, host, method, resourceType, thirdParty) }
      if (importantBlock != null) {
        totalBlockedCount.incrementAndGet()
        logSlowDecisionIfNeeded(startNs, resourceType)
        return BlockDecision(
          blocked = true,
          ruleId = "important_block:${importantBlock.raw}",
          ruleSource = "KotlinFallback",
          engineGeneration = currentGen,
          defaultMatched = true,
          defaultImportant = true
        )
      }

      // 3. Check Normal Exceptions (@@...)
      val normalException = fallbackNetworkRules.firstOrNull { it.isException && !it.isImportant && it.matches(lowerUrl, host, method, resourceType, thirdParty) }
      if (normalException != null) {
        logSlowDecisionIfNeeded(startNs, resourceType)
        return BlockDecision(
          blocked = false,
          ruleId = "exception:${normalException.raw}",
          ruleSource = "KotlinFallback",
          engineGeneration = currentGen,
          defaultException = true
        )
      }

      // 4. Check Normal Blocks
      val normalBlock = fallbackNetworkRules.firstOrNull { !it.isException && !it.isImportant && it.matches(lowerUrl, host, method, resourceType, thirdParty) }
      if (normalBlock != null) {
        totalBlockedCount.incrementAndGet()
        logSlowDecisionIfNeeded(startNs, resourceType)
        return BlockDecision(
          blocked = true,
          ruleId = "block:${normalBlock.raw}",
          ruleSource = "KotlinFallback",
          engineGeneration = currentGen,
          defaultMatched = true
        )
      }

      if (allowList.any { rule ->
        val cleanRule = rule.lowercase().trim()
        cleanRule.isNotEmpty() && (host == cleanRule || host.endsWith(".$cleanRule") || (cleanRule.length > 2 && lowerUrl.contains(cleanRule)))
      }) {
        logSlowDecisionIfNeeded(startNs, resourceType)
        return BlockDecision(
          blocked = false,
          ruleId = "allowlist",
          ruleSource = "KotlinFallback",
          engineGeneration = currentGen
        )
      }

      for (blockedHost in blockedHostnames) {
        if (host == blockedHost || host.endsWith(".$blockedHost")) {
          totalBlockedCount.incrementAndGet()
          logSlowDecisionIfNeeded(startNs, resourceType)
          return BlockDecision(
            blocked = true,
            ruleId = "host:$blockedHost",
            ruleSource = "KotlinFallback",
            engineGeneration = currentGen
          )
        }
      }

      for (pattern in blockedSubstrings) {
        if (lowerUrl.contains(pattern)) {
          totalBlockedCount.incrementAndGet()
          logSlowDecisionIfNeeded(startNs, resourceType)
          return BlockDecision(
            blocked = true,
            ruleId = "pattern:$pattern",
            ruleSource = "KotlinFallback",
            engineGeneration = currentGen
          )
        }
      }

      logSlowDecisionIfNeeded(startNs, resourceType)
      return BlockDecision(
        blocked = false,
        ruleId = "none",
        ruleSource = "KotlinFallback",
        engineGeneration = currentGen
      )
    } catch (t: Throwable) {
      Log.e(TAG, "[ADBLOCK_DECISION_ERROR] ${t.javaClass.name}: ${t.message}", t)
      throw t
    }
  }

  private fun logSlowDecisionIfNeeded(startNs: Long, resourceType: String) {
    val elapsedUs = (System.nanoTime() - startNs) / 1_000
    if (elapsedUs > 10_000) {
      Log.w(TAG, "Slow adblock decision: ${elapsedUs}us type=$resourceType")
    }
  }

  fun getApiVersion(): Int = nativeNumericApiVersion

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
  private external fun nativeMatchesJson(contextJson: String): String
  private external fun nativeCompileRules(defaultRules: String, additionalRules: String): String
  private external fun nativeGetCosmeticResources(url: String, classes: String, ids: String, exceptions: String, aggressive: Boolean): String
  private external fun nativeGetHiddenClassIdSelectors(classes: String, ids: String, exceptions: String): String
  private external fun nativeGetFilterCount(): Int
  private external fun nativeGetBlockedCount(): Int
  private external fun nativeGetGeneration(): Long
  private external fun nativeGetEngineGeneration(): Long
  private external fun nativeSelfTest(): Boolean
  private external fun nativeGetVersion(): String
  private external fun nativeGetApiVersion(): Int
  private external fun nativeGetBuildId(): String
  private external fun nativeGetAbi(): String

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

