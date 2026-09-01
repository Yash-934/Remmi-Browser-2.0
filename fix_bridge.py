import re

with open('app/src/main/java/com/remmi/adblock/AdblockBridge.kt', 'r') as f:
    content = f.read()

new_classes = """data class BlockDecision(
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
)"""

content = re.sub(r'data class BlockDecision\(.*?\)', new_classes, content, flags=re.DOTALL)

old_eval = """  fun evaluateDecision(url: String, sourceUrl: String = "", resourceType: String = "other"): BlockDecision {
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
      }"""

new_eval = """  fun evaluateDecision(
    url: String, 
    sourceUrl: String = "", 
    initiator: String = "",
    method: String = "GET",
    resourceType: String = "other",
    aggressive: Boolean = false,
    thirdParty: Boolean = true
  ): BlockDecision {
    val startNs = System.nanoTime()
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
          
          return BlockDecision(
            blocked = blocked,
            ruleId = "native",
            ruleSource = "RustEngine",
            engineGeneration = currentGen,
            redirectUrl = resultObj.optString("redirect", null).takeIf { it.isNotEmpty() },
            rewrittenUrl = resultObj.optString("rewrittenUrl", null).takeIf { it.isNotEmpty() },
            csp = resultObj.optString("csp", null).takeIf { it.isNotEmpty() },
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
      }"""

content = content.replace(old_eval, new_eval)

# Also update Kotlin fallback which takes less arguments, just pass url and resourceType
content = content.replace('      val blocked = fallbackEngine.matches(url, resourceType)', '      val blocked = fallbackEngine.matches(url, resourceType)')

content = content.replace('  private external fun nativeMatches(url: String, sourceUrl: String, requestType: String): Boolean', '  private external fun nativeMatchesJson(contextJson: String): String')

with open('app/src/main/java/com/remmi/adblock/AdblockBridge.kt', 'w') as f:
    f.write(content)

