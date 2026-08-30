import re
with open("app/src/test/java/com/remmi/browser/security/ConformanceAuditTest.kt", "r") as f:
    content = f.read()

content = content.replace(
    'println("TEST3 DECISION: blocked=${dec3.blocked} ruleId=${dec3.ruleId} source=${dec3.ruleSource}")',
    'println("TEST3 DECISION: blocked=${dec3.blocked} ruleId=${dec3.ruleId} source=${dec3.ruleSource} isNativeLoaded=${bridge.isNativeLoaded}")'
)

with open("app/src/test/java/com/remmi/browser/security/ConformanceAuditTest.kt", "w") as f:
    f.write(content)
