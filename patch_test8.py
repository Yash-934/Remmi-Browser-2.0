import re

with open("app/src/test/java/com/remmi/browser/security/EngineSeparationTest.kt", "r") as f:
    content = f.read()

old_test8 = """    @Test
    fun test8_proceduralFiltersNotExecuted() {
        val bridge = AdblockBridge.getInstance()
        bridge.compileRules("example.com#$#log('test')", "")
        
        val res = bridge.getCosmeticResources("https://example.com/")
        assertTrue(res.procedural.contains("log('test')"))
    }"""

new_test8 = """    @Test
    fun test7_shieldStandardVsAggressive() {
        val bridge = AdblockBridge.getInstance()
        bridge.compileRules("example.com#$#log('test')", "")
        
        val resStandard = bridge.getCosmeticResources("https://example.com/", aggressive = false)
        assertFalse("Standard mode should strip procedural from default engine", resStandard.procedural.contains("log('test')"))

        val resAggressive = bridge.getCosmeticResources("https://example.com/", aggressive = true)
        assertTrue("Aggressive mode should include procedural from default engine", resAggressive.procedural.contains("log('test')"))
    }"""

content = content.replace(old_test8, new_test8)

with open("app/src/test/java/com/remmi/browser/security/EngineSeparationTest.kt", "w") as f:
    f.write(content)
