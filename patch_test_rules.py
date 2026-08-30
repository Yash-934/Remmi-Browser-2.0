import re
with open("app/src/test/java/com/remmi/browser/security/ConformanceAuditTest.kt", "r") as f:
    content = f.read()

content = content.replace("@@||remmi-unbreak.invalid/safe^", "@@||remmi-unbreak.invalid/safe")
content = content.replace("@@||remmi-override.invalid/safe^", "@@||remmi-override.invalid/safe")
content = content.replace("@@||remmi-important.invalid/safe^", "@@||remmi-important.invalid/safe")
with open("app/src/test/java/com/remmi/browser/security/ConformanceAuditTest.kt", "w") as f:
    f.write(content)
