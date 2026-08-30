import re
with open("app/src/test/java/com/remmi/browser/security/ConformanceAuditTest.kt", "r") as f:
    content = f.read()

content = content.replace(
    'assertFalse(bridge.evaluateDecision("https://remmi-unbreak.invalid/safe/script.js").blocked)',
    'assertFalse(bridge.evaluateDecision("https://remmi-unbreak.invalid/safe/script.js", "https://remmi-unbreak.invalid/").blocked)'
)
content = content.replace(
    'assertFalse("Additional exception overrides default block", bridge.evaluateDecision("https://remmi-override.invalid/safe/script.js").blocked)',
    'assertFalse("Additional exception overrides default block", bridge.evaluateDecision("https://remmi-override.invalid/safe/script.js", "https://remmi-override.invalid/").blocked)'
)
with open("app/src/test/java/com/remmi/browser/security/ConformanceAuditTest.kt", "w") as f:
    f.write(content)
