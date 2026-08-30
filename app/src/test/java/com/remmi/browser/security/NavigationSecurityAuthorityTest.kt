package com.remmi.browser.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NavigationSecurityAuthorityTest {

  @Test
  fun testBlocksDangerousSchemes() {
    val dangerousUrls = listOf(
      "javascript:alert(1)",
      "data:text/html,<script>alert(1)</script>",
      "file:///sdcard/Download/secret.txt",
      "content://com.android.providers.media.documents/document/image%3A1",
      "chrome://version",
      "resource://android/res",
      "intent://example.com#Intent;scheme=http;package=com.android.chrome;end",
      "filesystem:http://example.com/temporary/myfile.txt",
      "blob:https://example.com/uuid-blob-target",
      "jar:file://test.jar!/"
    )

    for (url in dangerousUrls) {
      val res = NavigationSecurityAuthority.validateAndSanitizeNavigation(url, isGhost = false)
      assertEquals("Should block $url", NavigationDecision.BLOCK, res.decision)
    }
  }

  @Test
  fun testBlocksLoopbackAndPrivateAddresses() {
    val blockedHosts = listOf(
      "http://localhost",
      "http://127.0.0.1",
      "http://127.0.0.1:8080",
      "http://0.0.0.0",
      "http://[::1]",
      "http://192.168.1.1",
      "http://10.0.0.1",
      "http://172.16.0.1",
      "http://169.254.169.254/latest/meta-data/"
    )

    for (url in blockedHosts) {
      val res = NavigationSecurityAuthority.validateAndSanitizeNavigation(url, isGhost = false)
      assertEquals("Should block SSRF target $url", NavigationDecision.BLOCK, res.decision)
    }
  }

  @Test
  fun testAllowsSafePublicUrlsWithHttpsUpgrade() {
    val res = NavigationSecurityAuthority.validateAndSanitizeNavigation("http://example.com", isGhost = false)
    assertEquals(NavigationDecision.ALLOW, res.decision)
    assertEquals("https://example.com", res.sanitizedUrl)
  }

  @Test
  fun testOnionNavigationRequiresTorInGhostMode() {
    CurrentTorRoute.clearRoute()

    // Clearnet / Shield mode attempting .onion without verified Tor
    val res = NavigationSecurityAuthority.validateAndSanitizeNavigation("http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion", isGhost = false)
    assertEquals(NavigationDecision.BLOCK, res.decision)

    // With verified Tor active
    CurrentTorRoute.updateRoute(
      socksPort = 9050,
      isGhostActive = true,
      isVerified = true,
      exitIp = "185.220.101.5"
    )

    val resGhost = NavigationSecurityAuthority.validateAndSanitizeNavigation("http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion", isGhost = true)
    assertEquals(NavigationDecision.ALLOW, resGhost.decision)

    CurrentTorRoute.clearRoute()
  }
}
