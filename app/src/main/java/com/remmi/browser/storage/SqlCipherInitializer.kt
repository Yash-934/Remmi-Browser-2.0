package com.remmi.browser.storage

object SqlCipherInitializer {

    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return

        synchronized(this) {
            if (loaded) return

            try {
                System.loadLibrary("sqlcipher")
                loaded = true
            } catch (e: UnsatisfiedLinkError) {
                // In JVM unit tests or test environments without native libs, log and continue
                android.util.Log.w("SqlCipherInitializer", "Native sqlcipher library not loaded on this platform: ${e.message}")
            }
        }
    }
}
