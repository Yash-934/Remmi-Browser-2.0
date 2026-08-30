import re
with open("app/src/main/java/com/remmi/adblock/AdblockBridge.kt", "r") as f:
    content = f.read()

target = """      if (isNativeLoaded) {
        try {
          val blocked = nativeMatches(url, sourceUrl, resourceType)
          if (blocked) {
            totalBlockedCount.incrementAndGet()
            logSlowDecisionIfNeeded(startNs, resourceType)
            return BlockDecision(
              blocked = true,
              ruleId = "native",
              ruleSource = "RustEngine",
              engineGeneration = currentGen
            )
          }
        } catch (t: Throwable) {
          state = AdblockState.DEGRADED
          Log.e(TAG, "[ADBLOCK_DECISION_ERROR] ${t.javaClass.name}: ${t.message}", t)
          throw t
        }
      }"""

replacement = """      if (isNativeLoaded) {
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

content = content.replace(target, replacement)
with open("app/src/main/java/com/remmi/adblock/AdblockBridge.kt", "w") as f:
    f.write(content)
