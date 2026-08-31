package com.remmi.adblock

import android.util.Log
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
  val engineGeneration: Long = 0L
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

/**
 * Remmi Adblock Bridge
 * Bridges to native Rust adblock engine (libadblock_rust.so) with deterministic fallback to built-in rules.
 */
class AdblockBridge {

  private val blockedHostnames = ConcurrentHashMap.newKeySet<String>()
  private val blockedSubstrings = CopyOnWriteArrayList<String>()
  private val allowList = ConcurrentHashMap.newKeySet<String>()
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

  var isNativeHiddenClassIdCompatible: Boolean = false
    private set

  var isJniSignatureCompatible: Boolean = false
    private set

  var state: AdblockState = AdblockState.STARTING
    private set

  private val initialized = AtomicBoolean(false)

  init {
    initEngine()
  }

  fun isNativeAvailable(): Boolean = isNativeLoaded

  fun verifyNativeCompatibility(version: String, buildId: String, abi: String): Boolean {
    // Current Kotlin declares 3-argument nativeGetHiddenClassIdSelectors(classes, ids, exceptions).
    // Prebuilt .so binaries (version "adblock-rust-0.8.0-remmi" or legacy 0.8.0) contain the legacy 4-argument signature.
    // Fresh native .so rebuild (version >= "adblock-rust-0.8.1-remmi" or build flag "v2-compat") enables the 3-argument signature.
    // Old 0.8.0 binaries remain strictly gated (returning false) so the app does not call the incompatible JNI method.
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

  private fun initEngine() {
    try {
      System.loadLibrary("adblock_rust")
      val initSuccess = nativeInit()
      if (initSuccess) {
        isNativeLoaded = true
        state = AdblockState.READY
        Log.i(TAG, "Native adblock_rust loaded and initialized successfully!")

        try {
          nativeApiVersion = nativeGetVersion()
        } catch (_: Throwable) { nativeApiVersion = "unknown" }
        try {
          nativeBuildId = nativeGetBuildId()
        } catch (_: Throwable) { nativeBuildId = "unknown" }
        try {
          nativeAbi = nativeGetAbi()
        } catch (_: Throwable) { nativeAbi = "unknown" }

        isJniSignatureCompatible = verifyNativeCompatibility(nativeApiVersion, nativeBuildId, nativeAbi)
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

    loadDefaultTrackerRules()

    if (isNativeLoaded) {
      selfTest()
    }
  }

  suspend fun initialize(): Boolean {
    if (initialized.get()) {
      return true
    }

    return try {
      Log.d(TAG, "[ADBLOCK_FILTER_LOAD_START]")
      loadDefaultTrackerRules()

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

      initialized.set(true)
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
      val ok = nativeSelfTest()
      Log.d(TAG, "[ADBLOCK_SELF_TEST] native=true deterministic=$ok")
      if (!ok) {
        Log.e(TAG, "[ADBLOCK_SELF_TEST] deterministic_self_test_failed")
      }
      ok
    } catch (t: Throwable) {
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

  fun loadDefaultTrackerRules() {
    allowList.clear()
    fallbackCosmeticExceptions.clear()

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
        nativeCompileRules(rulesText, "")
      } catch (e: Throwable) {
        Log.e(TAG, "Failed to compile default rules into native engine", e)
      }
    }
  }

  fun addCustomRule(rule: String) {
    val trimmed = rule.trim()
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
        compiledCount = nativeCompileRules(combinedDefaultRulesText, additionalRulesText)
      } catch (e: Throwable) {
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

    // Re-seed default tracker domains in Kotlin fallback
    blockedHostnames.addAll(defaultDomains)
    blockedSubstrings.addAll(defaultPatterns)

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
          } else if (trimmed.startsWith("@@")) {
            val clean = trimmed.removePrefix("@@").removePrefix("||").removeSuffix("^").trim()
            if (clean.isNotEmpty()) allowList.add(clean)
          } else if (trimmed.startsWith("||")) {
            val clean = trimmed.removePrefix("||").removeSuffix("^").trim()
            if (clean.isNotEmpty()) blockedHostnames.add(clean)
          } else {
            blockedSubstrings.add(trimmed)
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
        val resultJson = nativeGetCosmeticResources(url, classesJson, idsJson, exceptionsJson, aggressive)
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
        val resultJson = nativeGetHiddenClassIdSelectors(classesJson, idsJson, exceptionsJson)
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
    return evaluateDecision(url, sourceUrl, resourceType).blocked
  }

  fun evaluateDecision(url: String, sourceUrl: String = "", resourceType: String = "other"): BlockDecision {
    val startNs = System.nanoTime()
    val currentGen = getEngineGeneration()
    try {
      if (isNativeLoaded) {
        try {
          val blocked = nativeMatches(url, sourceUrl, resourceType)
          if (blocked) {
            totalBlockedCount.incrementAndGet()
          }
          logSlowDecisionIfNeeded(startNs, resourceType)
          return BlockDecision(
            blocked = blocked,
            ruleId = "native",
            ruleSource = "RustEngine",
            engineGeneration = currentGen
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
  private external fun nativeCompileRules(defaultRules: String, additionalRules: String): Int
  private external fun nativeGetCosmeticResources(url: String, classes: String, ids: String, exceptions: String, aggressive: Boolean): String
  private external fun nativeGetHiddenClassIdSelectors(classes: String, ids: String, exceptions: String): String
  private external fun nativeGetFilterCount(): Int
  private external fun nativeGetBlockedCount(): Int
  private external fun nativeGetGeneration(): Long
  private external fun nativeGetEngineGeneration(): Long
  private external fun nativeSelfTest(): Boolean
  private external fun nativeGetVersion(): String
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

