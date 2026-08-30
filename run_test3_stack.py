import subprocess
out = subprocess.run(["gradle", ":app:testDebugUnitTest", "--tests", "com.remmi.browser.security.ConformanceAuditTest.test3_defaultExceptionAndAdditionalEngine", "-i"], capture_output=True, text=True)
for line in out.stdout.split('\n'):
    if "ConformanceAuditTest.kt:" in line or "FAILED" in line:
        print(line)
