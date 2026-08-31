import re

with open('app/src/test/java/com/remmi/browser/StartupIsolationMatrixTest.kt', 'r') as f:
    content = f.read()

content = content.replace("CrashHandlerHelper.currentPhase = StartupPhase.UNKNOWN", "CrashHandlerHelper.updateStartupPhase(phase = StartupPhase.PROCESS_START)")

with open('app/src/test/java/com/remmi/browser/StartupIsolationMatrixTest.kt', 'w') as f:
    f.write(content)
