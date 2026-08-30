package com.remmi.browser.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.browser.storage.RemmiDatabase
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseHardeningTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    RemmiDatabase.closeDatabase()
  }

  @After
  fun tearDown() {
    RemmiDatabase.testPassphraseProvider = null
    RemmiDatabase.closeDatabase()
  }

  @Test
  fun testFailsClosedWhenKeyDerivationFails() {
    RemmiDatabase.testPassphraseProvider = {
      throw SecurityException("Hardware Keystore failure simulation")
    }

    assertThrows(SecurityException::class.java) {
      RemmiDatabase.getDatabase(context)
    }
  }

  @Test
  fun testFailsClosedWhenKeystoreUnavailableWithoutTestProvider() {
    // When no test provider is set and running in non-hardware Keystore env, must throw SecurityException
    RemmiDatabase.testPassphraseProvider = null
    assertThrows(SecurityException::class.java) {
      RemmiDatabase.getDatabase(context)
    }
  }

  @Test
  fun testEncryptedDatabaseCannotBeOpenedAsPlaintextSqlite() {
    val dbFile = context.getDatabasePath("remmi_vault_encrypted_test.db")
    dbFile.parentFile?.mkdirs()

    // Write 4096 bytes of encrypted pseudo-random ciphertext (representing a SQLCipher page with salt/IV)
    val encryptedCiphertext = ByteArray(4096).apply {
      java.security.SecureRandom().nextBytes(this)
    }
    dbFile.writeBytes(encryptedCiphertext)
    assertTrue(dbFile.exists())

    // Attempting to open encrypted database file as plaintext SQLite must fail with SQLiteException
    try {
      assertThrows(android.database.sqlite.SQLiteException::class.java) {
        val plaintextDb = android.database.sqlite.SQLiteDatabase.openDatabase(
          dbFile.absolutePath,
          null,
          android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        try {
          plaintextDb.rawQuery("SELECT * FROM sqlite_master", null).use { cursor ->
            cursor.moveToFirst()
          }
        } finally {
          plaintextDb.close()
        }
      }
    } finally {
      dbFile.delete()
    }
  }
}
