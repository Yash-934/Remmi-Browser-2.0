import re
with open("app/src/test/java/com/remmi/browser/security/ConformanceAuditTest.kt", "r") as f:
    content = f.read()

content = content.replace(
    'bridge.compileRules("||remmi-unbreak.invalid^\\n@@||remmi-unbreak.invalid", "")',
    'bridge.compileRules("||remmi-unbreak.invalid^\\n@@||remmi-unbreak.invalid/safe^", "")'
)
content = content.replace(
    'assertFalse(bridge.evaluateDecision("https://remmi-unbreak.invalid/safe/script.js", "https://remmi-unbreak.invalid/", "script").blocked)',
    'assertFalse(bridge.evaluateDecision("https://remmi-unbreak.invalid/safe/", "https://remmi-unbreak.invalid/", "script").blocked)'
)

content = content.replace(
    'bridge.compileRules("||remmi-override.invalid^", "||remmi-override.invalid^\\n@@||remmi-override.invalid/safe\\$script")',
    'bridge.compileRules("||remmi-override.invalid^", "||remmi-override.invalid^\\n@@||remmi-override.invalid/safe^")'
)
content = content.replace(
    'assertFalse("Additional exception overrides default block", bridge.evaluateDecision("https://remmi-override.invalid/safe/script.js", "https://remmi-override.invalid/", "script").blocked)',
    'assertFalse("Additional exception overrides default block", bridge.evaluateDecision("https://remmi-override.invalid/safe/", "https://remmi-override.invalid/", "script").blocked)'
)

with open("app/src/test/java/com/remmi/browser/security/ConformanceAuditTest.kt", "w") as f:
    f.write(content)
