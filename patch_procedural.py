import re

with open("app/src/main/java/com/remmi/adblock/AdblockBridge.kt", "r") as f:
    content = f.read()

# Add proceduralList variable for fallback
compile_func = """          if (trimmed.contains("#@#")) {
            fallbackCosmeticExceptions.add(trimmed)
          } else if (trimmed.contains("#$#")) {
            // Procedural (we don't execute in fallback, but just store if needed)
          }"""

compile_func_new = """          if (trimmed.contains("#@#")) {
            fallbackCosmeticExceptions.add(trimmed)
          } else if (trimmed.contains("#$#")) {
            val parts = trimmed.split("#$#", limit = 2)
            if (parts.size == 2 && parts[1].isNotBlank()) {
              fallbackProceduralFilters.add(parts[1].trim())
            }
          }"""
content = content.replace(compile_func, compile_func_new)
content = content.replace("private val fallbackAdditionalCosmeticRules = java.util.concurrent.CopyOnWriteArrayList<Pair<String?, String>>()",
    "private val fallbackAdditionalCosmeticRules = java.util.concurrent.CopyOnWriteArrayList<Pair<String?, String>>()\n  private val fallbackProceduralFilters = java.util.concurrent.CopyOnWriteArrayList<String>()")
content = content.replace("fallbackAdditionalCosmeticRules.clear()", "fallbackAdditionalCosmeticRules.clear()\n    fallbackProceduralFilters.clear()")
content = content.replace("val proceduralList = mutableListOf<String>()", "val proceduralList = fallbackProceduralFilters.toList()")

with open("app/src/main/java/com/remmi/adblock/AdblockBridge.kt", "w") as f:
    f.write(content)
