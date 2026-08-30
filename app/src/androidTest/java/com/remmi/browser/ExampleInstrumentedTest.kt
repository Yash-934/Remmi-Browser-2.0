package com.remmi.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.remmi.adblock.AdblockBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
  @Test
  fun useAppContext() {
    // Context of the app under test.
    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)
  }

  @Test
  fun nativeAdblockLibraryLoadsOnDevice() {
    val adblockBridge = AdblockBridge.getInstance()
    assertTrue("Native adblock library should be available on Android device", adblockBridge.isNativeAvailable())
    assertTrue("Native adblock self-test must succeed on Android device", adblockBridge.selfTest())
  }
}
