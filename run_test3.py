import subprocess
out = subprocess.run(["gradle", ":app:testDebugUnitTest", "--tests", "com.remmi.browser.security.ConformanceAuditTest.test3_defaultExceptionAndAdditionalEngine", "-i"], capture_output=True, text=True)
print([line for line in out.stdout.split('\n') if 'FAILED' in line or 'SUCCESS' in line])
