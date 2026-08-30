import re
with open("app/src/test/java/com/remmi/browser/security/ConformanceAuditTest.kt", "r") as f:
    content = f.read()

content = content.replace(
    'bridge.compileRules("||remmi-unbreak.invalid^\\n@@||remmi-unbreak.invalid/safe\\$script", "")',
    'bridge.compileRules("||remmi-unbreak.invalid^\\n@@||remmi-unbreak.invalid/safe/script.js", "")'
)

with open("app/src/test/java/com/remmi/browser/security/ConformanceAuditTest.kt", "w") as f:
    f.write(content)
