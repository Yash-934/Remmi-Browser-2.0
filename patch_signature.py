import re
with open("app/src/main/java/com/remmi/adblock/AdblockBridge.kt", "r") as f:
    content = f.read()

content = content.replace("private external fun nativeGetCosmeticResources(\n    url: String,\n    classesJson: String,\n    idsJson: String,\n    exceptionsJson: String\n  ): String", "private external fun nativeGetCosmeticResources(\n    url: String,\n    classesJson: String,\n    idsJson: String,\n    exceptionsJson: String,\n    aggressive: Boolean\n  ): String")
content = content.replace("private external fun nativeGetCosmeticResources(url: String, classes: String, ids: String, exceptions: String): String", "private external fun nativeGetCosmeticResources(url: String, classes: String, ids: String, exceptions: String, aggressive: Boolean): String")

with open("app/src/main/java/com/remmi/adblock/AdblockBridge.kt", "w") as f:
    f.write(content)
