import subprocess
out = subprocess.run(["gradle", ":app:testDebugUnitTest", "--tests", "com.remmi.browser.security.ConformanceAuditTest", "-i"], capture_output=True, text=True)
for line in out.stdout.split('\n'):
    if "FAILED" in line:
        print(line)
