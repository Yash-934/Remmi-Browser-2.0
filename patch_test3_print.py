import re
with open("app/src/test/java/com/remmi/browser/security/ConformanceAuditTest.kt", "r") as f:
    content = f.read()

content = content.replace(
    'assertFalse(bridge.evaluateDecision("https://remmi-unbreak.invalid/safe/", "https://remmi-unbreak.invalid/", "script").blocked)',
    '''val dec3 = bridge.evaluateDecision("https://remmi-unbreak.invalid/safe/", "https://remmi-unbreak.invalid/", "script")
        println("TEST3 DECISION: blocked=${dec3.blocked} ruleId=${dec3.ruleId} source=${dec3.ruleSource}")
        assertFalse("Test 3 failed: ${dec3}", dec3.blocked)'''
)

content = content.replace(
    'assertFalse("Additional exception overrides default block", bridge.evaluateDecision("https://remmi-override.invalid/safe/", "https://remmi-override.invalid/", "script").blocked)',
    '''val dec4 = bridge.evaluateDecision("https://remmi-override.invalid/safe/", "https://remmi-override.invalid/", "script")
        println("TEST4 DECISION: blocked=${dec4.blocked} ruleId=${dec4.ruleId} source=${dec4.ruleSource}")
        assertFalse("Test 4 failed: ${dec4}", dec4.blocked)'''
)

with open("app/src/test/java/com/remmi/browser/security/ConformanceAuditTest.kt", "w") as f:
    f.write(content)
