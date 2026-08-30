import subprocess
out = subprocess.run(["gradle", ":app:testDebugUnitTest", "--tests", "com.remmi.browser.security.ConformanceAuditTest.test1_defaultEngineBlock", "-i"], capture_output=True, text=True)
for line in out.stdout.split('\n'):
    if "AdblockBridge" in line:
        print(line)
