package com.remmi.browser.security

import com.remmi.browser.reader.ReaderExtractor
import com.remmi.browser.reader.ReaderTranslator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderSecurityHardeningTest {

  @Test
  fun testReaderBlocksDangerousSchemesAndPrivateAddresses() = runBlocking {
    CurrentTorRoute.clearRoute()

    // Test SSRF / Local target blocking
    val privateArticle = ReaderExtractor.extractFromUrl("http://127.0.0.1:8080/admin", "Admin", isGhost = false)
    assertTrue(privateArticle.paragraphs.any { it.text.contains("Local/Private address targets are prohibited") || it.text.contains("blocked") })

    val schemeArticle = ReaderExtractor.extractFromUrl("javascript:alert(1)", "XSS", isGhost = false)
    assertTrue(schemeArticle.paragraphs.any { it.text.contains("Unsupported or unsafe URL scheme") || it.text.contains("blocked") })
  }

  @Test
  fun testReaderBlocksUnverifiedTorGhostRoutes() = runBlocking {
    CurrentTorRoute.clearRoute()

    // Unverified Tor in Ghost mode must fail closed
    val ghostArticle = ReaderExtractor.extractFromUrl("https://example.com/news", "News", isGhost = true)
    assertTrue(ghostArticle.paragraphs.any { it.text.contains("Tor route is not verified") || it.text.contains("blocked") })

    val onionArticle = ReaderExtractor.extractFromUrl("http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion", "Onion", isGhost = false)
    assertTrue(onionArticle.paragraphs.any { it.text.contains("Tor route is not verified") || it.text.contains("blocked") })
  }

  @Test
  fun testTranslatorBlocksUnverifiedTorGhostRoutes() = runBlocking {
    CurrentTorRoute.clearRoute()

    val originalText = "Sensitive confidential document text"
    val result = ReaderTranslator.translateText(originalText, "es", isGhost = true)
    // Must return original text without clearnet leak
    assertEquals(originalText, result)
  }

  @Test
  fun testReaderArticleParsingBounds() {
    val hugeHtml = buildString {
      append("<html><body><h1>Big Article</h1>")
      for (i in 1..1000) {
        append("<p>Paragraph $i: Some long content with detailed information about various events.</p>")
      }
      append("</body></html>")
    }

    val article = ReaderExtractor.parseHtmlDocument(hugeHtml, "https://example.com/big", "Title", "example.com")
    assertTrue("Paragraphs must be capped at 500", article.paragraphs.size <= 500)
  }
}
