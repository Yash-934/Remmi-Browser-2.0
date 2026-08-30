import re

with open("app/src/main/java/com/remmi/adblock/AdblockBridge.kt", "r") as f:
    content = f.read()

# Make fallback separate lists
content = content.replace("private val fallbackCosmeticRules = CopyOnWriteArrayList<Pair<String?, String>>()",
    "private val fallbackCosmeticRules = java.util.concurrent.CopyOnWriteArrayList<Pair<String?, String>>()\n  private val fallbackAdditionalCosmeticRules = java.util.concurrent.CopyOnWriteArrayList<Pair<String?, String>>()")

# Fix fallback parsing to separate lists
compile_func_old = """    // Also parse into Kotlin memory fallback
    val fallbackRulesText = "$combinedDefaultRulesText\\n$additionalRulesText"
    if (fallbackRulesText.isNotBlank()) {
      fallbackRulesText.lines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("!")) {
          if (trimmed.contains("#@#")) {
            fallbackCosmeticExceptions.add(trimmed)
          } else if (trimmed.contains("##")) {
            val parts = trimmed.split("##", limit = 2)
            val domain = parts[0].trim().ifEmpty { null }
            val selector = parts[1].trim()
            if (selector.isNotEmpty()) {
              fallbackCosmeticRules.add(Pair(domain, selector))
            }
          } else if (trimmed.startsWith("@@")) {
            allowList.add(trimmed.removePrefix("@@").removePrefix("||").removeSuffix("^"))
          } else if (trimmed.startsWith("||")) {
            blockedHostnames.add(trimmed.removePrefix("||").removeSuffix("^"))
          } else {
            blockedSubstrings.add(trimmed)
          }
          if (!isNativeLoaded) compiledCount++
        }
      }
    }"""

compile_func_new = """    // Also parse into Kotlin memory fallback
    fallbackAdditionalCosmeticRules.clear()
    
    fun parseToFallback(rules: String, isAdditional: Boolean) {
      if (rules.isBlank()) return
      rules.lines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("!")) {
          if (trimmed.contains("#@#")) {
            fallbackCosmeticExceptions.add(trimmed)
          } else if (trimmed.contains("#$#")) {
            // Procedural (we don't execute in fallback, but just store if needed)
          } else if (trimmed.contains("##")) {
            val parts = trimmed.split("##", limit = 2)
            val domain = parts[0].trim().ifEmpty { null }
            val selector = parts[1].trim()
            if (selector.isNotEmpty()) {
              if (isAdditional) fallbackAdditionalCosmeticRules.add(Pair(domain, selector))
              else fallbackCosmeticRules.add(Pair(domain, selector))
            }
          } else if (trimmed.startsWith("@@")) {
            allowList.add(trimmed.removePrefix("@@").removePrefix("||").removeSuffix("^"))
          } else if (trimmed.startsWith("||")) {
            blockedHostnames.add(trimmed.removePrefix("||").removeSuffix("^"))
          } else {
            blockedSubstrings.add(trimmed)
          }
          if (!isNativeLoaded) compiledCount++
        }
      }
    }
    
    parseToFallback(combinedDefaultRulesText, false)
    parseToFallback(additionalRulesText, true)"""

content = content.replace(compile_func_old, compile_func_new)

# Fix Kotlin fallback cosmetic resolving
cosmetic_old = """    val hideList = mutableListOf<String>()
    for ((domain, selector) in fallbackCosmeticRules) {
      if (domain == null) {
        // Generic rule
        hideList.add(selector)
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
          hideList.add(selector)
        }
      }
    }

    // Apply exceptions
    for (ex in fallbackCosmeticExceptions) {
      val parts = ex.split("#@#", limit = 2)
      if (parts.size == 2) {
        val exDomain = parts[0].trim().lowercase()
        val exSelector = parts[1].trim()
        if (exDomain.isEmpty() || host == exDomain || host.endsWith(".$exDomain")) {
          hideList.remove(exSelector)
        }
      }
    }

    return CosmeticResources(
      ok = true,
      generation = currentGen,
      hideSelectors = hideList.distinct(),
      forceHideSelectors = emptyList(),
      procedural = emptyList(),
      proceduralCount = 0,
      generics = true,
      error = null
    )"""

cosmetic_new = """    val hideList = mutableListOf<String>()
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
    val proceduralList = mutableListOf<String>()
    
    return CosmeticResources(
      ok = true,
      generation = currentGen,
      hideSelectors = hideList.distinct(),
      forceHideSelectors = forceHideList.distinct(),
      procedural = proceduralList,
      proceduralCount = proceduralList.size,
      generics = true,
      error = null
    )"""

content = content.replace(cosmetic_old, cosmetic_new)

with open("app/src/main/java/com/remmi/adblock/AdblockBridge.kt", "w") as f:
    f.write(content)
