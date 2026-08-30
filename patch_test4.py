import re
with open("app/src/test/java/com/remmi/browser/security/ConformanceAuditTest.kt", "r") as f:
    content = f.read()

content = content.replace(
    'bridge.compileRules("||remmi-override.invalid^", "@@||remmi-override.invalid/safe")',
    'bridge.compileRules("||remmi-override.invalid^", "||remmi-override.invalid^\\n@@||remmi-override.invalid/safe")'
)
content = content.replace(
    'bridge.compileRules("||remmi-important.invalid^\\$important", "@@||remmi-important.invalid/safe")',
    'bridge.compileRules("||remmi-important.invalid^\\$important", "||remmi-important.invalid^\\n@@||remmi-important.invalid/safe")'
)
with open("app/src/test/java/com/remmi/browser/security/ConformanceAuditTest.kt", "w") as f:
    f.write(content)
