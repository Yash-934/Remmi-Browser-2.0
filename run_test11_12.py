import subprocess
out = subprocess.run(["gradle", ":app:testDebugUnitTest", "--tests", "com.remmi.browser.security.ConformanceAuditTest.test11_standardMode", "-i"], capture_output=True, text=True)
print("TEST11:")
print([line for line in out.stdout.split('\n') if 'FAILED' in line or 'SUCCESS' in line])

out2 = subprocess.run(["gradle", ":app:testDebugUnitTest", "--tests", "com.remmi.browser.security.ConformanceAuditTest.test12_aggressiveMode", "-i"], capture_output=True, text=True)
print("TEST12:")
print([line for line in out2.stdout.split('\n') if 'FAILED' in line or 'SUCCESS' in line])
