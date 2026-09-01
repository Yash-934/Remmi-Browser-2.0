import re

with open('app/src/main/java/com/remmi/adblock/BlockExtension.kt', 'r') as f:
    content = f.read()

content = content.replace(
    'val url = messageJson.optString("url")\n    val sourceUrl = messageJson.optString("sourceUrl")',
    'val url = messageJson.optString("url")\n    val sourceUrl = messageJson.optString("sourceUrl")\n    val initiator = messageJson.optString("initiator")\n    val method = messageJson.optString("method", "GET")\n    val aggressive = messageJson.optBoolean("aggressive", false)\n    val thirdParty = messageJson.optBoolean("thirdParty", true)'
)

content = content.replace(
    'adblockBridge.evaluateDecision(\n            url = url,\n            sourceUrl = sourceUrl,\n            resourceType = resourceType\n          )',
    'adblockBridge.evaluateDecision(\n            url = url,\n            sourceUrl = sourceUrl,\n            initiator = initiator,\n            method = method,\n            resourceType = resourceType,\n            aggressive = aggressive,\n            thirdParty = thirdParty\n          )'
)

# And now inject the actual NativeMatchResult fields back into result object:
new_result = """        result.complete(
          JSONObject().apply {
            put("ok", true)
            put("cancel", decision.blocked)
            put("generation", decision.engineGeneration)
            if (decision.redirectUrl != null) put("redirect", decision.redirectUrl)
            if (decision.rewrittenUrl != null) put("rewrittenUrl", decision.rewrittenUrl)
            if (decision.csp != null) put("csp", decision.csp)
          }
        )"""

content = re.sub(r'        result.complete\(\n          JSONObject\(\)\.apply \{\n            put\("ok", true\)\n            put\("cancel", decision\.blocked\)\n            put\("generation", decision\.engineGeneration\)\n          \}\n        \)', new_result, content)

with open('app/src/main/java/com/remmi/adblock/BlockExtension.kt', 'w') as f:
    f.write(content)
