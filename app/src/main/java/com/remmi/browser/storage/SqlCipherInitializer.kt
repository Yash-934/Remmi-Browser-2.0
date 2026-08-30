package com.remmi.browser.storage

object SqlCipherInitializer {

    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return

        synchronized(this) {
            if (loaded) return

            System.loadLibrary("sqlcipher")
            loaded = true
        }
    }
}
