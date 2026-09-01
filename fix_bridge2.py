import re

with open('app/src/main/java/com/remmi/adblock/AdblockBridge.kt', 'r') as f:
    content = f.read()

old_should_block = """  fun shouldBlock(url: String, sourceUrl: String = "", resourceType: String = "other"): Boolean {
    return evaluateDecision(url, sourceUrl, resourceType).blocked
  }

  fun evaluateDecision(url: String, sourceUrl: String = "", resourceType: String = "other"): BlockDecision {"""

new_should_block = """  fun shouldBlock(url: String, sourceUrl: String = "", resourceType: String = "other"): Boolean {
    return evaluateDecision(url, sourceUrl, resourceType = resourceType).blocked
  }

  fun evaluateDecision(
    url: String, 
    sourceUrl: String = "", 
    initiator: String = "",
    method: String = "GET",
    resourceType: String = "other",
    aggressive: Boolean = false,
    thirdParty: Boolean = true
  ): BlockDecision {"""

content = content.replace(old_should_block, new_should_block)

old_try = """    try {
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
        } catch (t: Throwable) {"""

new_try = """    try {
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
        } catch (t: Throwable) {"""

content = content.replace(old_try, new_try)

with open('app/src/main/java/com/remmi/adblock/AdblockBridge.kt', 'w') as f:
    f.write(content)
