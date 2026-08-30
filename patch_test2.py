import re

with open("app/src/main/java/com/remmi/adblock/AdblockBridge.kt", "r") as f:
    content = f.read()

content = content.replace("val allowed = allowList.any { allow ->", "val allowed = allowList.any { allow ->\n          android.util.Log.e(\"AdblockTest\", \"Checking allow: $allow against $url\")")
content = content.replace("if (allowed) {", "android.util.Log.e(\"AdblockTest\", \"Is allowed? $allowed\")\n        if (allowed) {")

with open("app/src/main/java/com/remmi/adblock/AdblockBridge.kt", "w") as f:
    f.write(content)
