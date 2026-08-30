package com.remmi.browser.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remmi.adblock.AdblockBridge
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConformanceAuditTest {

    private lateinit var bridge: AdblockBridge

    @Before
    fun setup() {
        bridge = AdblockBridge.getInstance()
    }

    @Test
    fun test1_defaultEngineBlock() {
        bridge.compileRules("||remmi-default-block.invalid^", "")
        assertTrue(bridge.evaluateDecision("https://remmi-default-block.invalid/a.js").blocked)
    }

    @Test
    fun test2_additionalEngineBlock() {
        bridge.compileRules("", "||remmi-additional-block.invalid^")
        assertTrue(bridge.evaluateDecision("https://remmi-additional-block.invalid/a.js").blocked)
    }

    @Test
    fun test3_defaultExceptionAndAdditionalEngine() {
        // Default has unbreak rule (exception). 
        bridge.compileRules("||remmi-unbreak.invalid^\n@@||remmi-unbreak.invalid/safe^", "")
        assertTrue(bridge.evaluateDecision("https://remmi-unbreak.invalid/a.js").blocked)
        val dec3 = bridge.evaluateDecision("https://remmi-unbreak.invalid/safe/", "https://remmi-unbreak.invalid/", "script")
        println("TEST3 DECISION: blocked=${dec3.blocked} ruleId=${dec3.ruleId} source=${dec3.ruleSource} isNativeLoaded=${bridge.isNativeLoaded}")
        assertFalse("Test 3 failed: ${dec3}", dec3.blocked)
    }

    @Test
    fun test4_previousMatchedExceptionPropagation() {
        // Additional engine allows, overriding default engine's block
        bridge.compileRules("||remmi-override.invalid^", "||remmi-override.invalid^\n@@||remmi-override.invalid/safe^")
        assertTrue(bridge.evaluateDecision("https://remmi-override.invalid/a.js").blocked)
        val dec4 = bridge.evaluateDecision("https://remmi-override.invalid/safe/", "https://remmi-override.invalid/", "script")
        println("TEST4 DECISION: blocked=${dec4.blocked} ruleId=${dec4.ruleId} source=${dec4.ruleSource}")
        assertFalse("Test 4 failed: ${dec4}", dec4.blocked)
    }

    @Test
    fun test5_importantRuleSemantics() {
        // Default engine has an important block rule
        // Additional engine tries to exception it out
        bridge.compileRules("||remmi-important.invalid^\$important", "||remmi-important.invalid^\n@@||remmi-important.invalid/safe")
        
        // Wait, does adblock-rust support \$important? Yes.
        // If default engine is important, additional engine shouldn't override it.
        assertTrue("Important rule in default must not be overridden by weak additional exception", bridge.evaluateDecision("https://remmi-important.invalid/safe/script.js").blocked)
    }

    @Test
    fun test7_defaultCosmetic() {
        bridge.compileRules("example.invalid##.remmi-default-ad", "")
        val res = bridge.getCosmeticResources("https://example.invalid/")
        assertTrue(res.hideSelectors.contains(".remmi-default-ad"))
    }

    @Test
    fun test8_additionalCosmetic() {
        bridge.compileRules("", "example.invalid##.remmi-additional-ad")
        val res = bridge.getCosmeticResources("https://example.invalid/")
        assertTrue(res.forceHideSelectors.contains(".remmi-additional-ad"))
    }

    @Test
    fun test9_cosmeticException() {
        bridge.compileRules("##.remmi-default-ad\nexample.invalid#@#.remmi-default-ad", "")
        val res = bridge.getCosmeticResources("https://example.invalid/")
        assertFalse(res.hideSelectors.contains(".remmi-default-ad"))
    }

    @Test
    fun test11_standardMode() {
        bridge.compileRules("example.invalid#$#log('test')", "")
        val res = bridge.getCosmeticResources("https://example.invalid/", aggressive = false)
        assertFalse("Standard mode strips default procedural filters", res.procedural.contains("log('test')"))
    }

    @Test
    fun test12_aggressiveMode() {
        bridge.compileRules("example.invalid#$#log('test')", "")
        val res = bridge.getCosmeticResources("https://example.invalid/", aggressive = true)
        assertTrue("Aggressive mode keeps default procedural filters", res.procedural.contains("log('test')"))
    }
}
