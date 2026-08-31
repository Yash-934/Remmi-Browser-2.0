import re

with open('app/src/test/java/com/remmi/browser/StartupIsolationMatrixTest.kt', 'r') as f:
    content = f.read()

new_setup = """  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    Thread.sleep(1000) // Let background initialization finish so it doesn't concurrently mutate state
    context.getSharedPreferences(CrashHandlerHelper.PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
    DebugLogManager.init(context)
    DebugLogManager.clear()
    SqlCipherInitializer.resetForTesting()
    CrashHandlerHelper.currentPhase = StartupPhase.UNKNOWN
  }"""

content = re.sub(r'  @Before\n  fun setup\(\) \{.*?\n  \}', new_setup, content, flags=re.DOTALL)

with open('app/src/test/java/com/remmi/browser/StartupIsolationMatrixTest.kt', 'w') as f:
    f.write(content)
