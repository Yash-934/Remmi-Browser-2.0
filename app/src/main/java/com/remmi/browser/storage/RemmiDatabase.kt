package com.remmi.browser.storage


import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName = "history")
data class HistoryItem(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val url: String,
  val title: String,
  val timestamp: Long = System.currentTimeMillis(),
  val profile: String = "SHIELD",
)

@Entity(tableName = "bookmarks")
data class BookmarkItem(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val url: String,
  val title: String,
  val category: String = "General",
  val timestamp: Long = System.currentTimeMillis(),
)

@Entity(tableName = "blocked_events")
data class BlockedEvent(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val url: String,
  val domain: String,
  val timestamp: Long = System.currentTimeMillis(),
  val tabId: String,
)

@Entity(tableName = "downloads")
data class DownloadItem(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val downloadId: Long = 0,
  val fileName: String,
  val url: String,
  val mimeType: String = "",
  val fileSize: Long = 0,
  val timestamp: Long = System.currentTimeMillis(),
  val status: String = "DOWNLOADING",
  val filePath: String = "",
)

@Entity(
  tableName = "saved_readings",
  indices = [
    Index(value = ["folder"]),
    Index(value = ["saved_at"])
  ]
)
data class ReadingListItem(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  @ColumnInfo(name = "url") val url: String,
  @ColumnInfo(name = "title") val title: String,
  @ColumnInfo(name = "domain") val domain: String,
  @ColumnInfo(name = "site_name") val siteName: String = "",
  @ColumnInfo(name = "byline") val byline: String = "",
  @ColumnInfo(name = "excerpt") val excerpt: String = "",
  @ColumnInfo(name = "content_json") val contentJson: String = "",
  @ColumnInfo(name = "folder") val folder: String = "General",
  @ColumnInfo(name = "topic") val topic: String = "General",
  @ColumnInfo(name = "reading_time_minutes") val readingTimeMinutes: Int = 1,
  @ColumnInfo(name = "word_count") val wordCount: Int = 0,
  @ColumnInfo(name = "lead_image_url") val leadImageUrl: String? = null,
  @ColumnInfo(name = "saved_at") val savedAt: Long = System.currentTimeMillis(),
  @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
  @ColumnInfo(name = "is_read") val isRead: Boolean = false,
  @ColumnInfo(name = "last_read_at") val lastReadAt: Long? = null,
  @ColumnInfo(name = "notes") val notes: String = "",
)

@Entity(tableName = "session_tabs")
data class SessionTabEntity(
  @PrimaryKey val id: String,
  val url: String,
  val title: String,
  val position: Int,
  val timestamp: Long = System.currentTimeMillis(),
  val profile: String = "SHIELD",
  val isDesktopMode: Boolean = false,
  val isReaderMode: Boolean = false,
)

@Entity(
  tableName = "password_entries",
  indices = [Index(value = ["site_url_hash"])]
)
data class PasswordEntryEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  @ColumnInfo(name = "site_url_hash") val siteUrlHash: String,
  @ColumnInfo(name = "site_url_encrypted", typeAffinity = ColumnInfo.BLOB) val siteUrlEncrypted: ByteArray,
  @ColumnInfo(name = "username", typeAffinity = ColumnInfo.BLOB) val usernameEncrypted: ByteArray,
  @ColumnInfo(name = "password", typeAffinity = ColumnInfo.BLOB) val passwordEncrypted: ByteArray,
  @ColumnInfo(name = "notes", typeAffinity = ColumnInfo.BLOB) val notesEncrypted: ByteArray,
  @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
  @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
  @ColumnInfo(name = "iv", typeAffinity = ColumnInfo.BLOB) val iv: ByteArray,
  @ColumnInfo(name = "auth_tag", typeAffinity = ColumnInfo.BLOB) val authTag: ByteArray,
)

@Entity(tableName = "master_key_metadata")
data class MasterKeyMetadataEntity(
  @PrimaryKey val id: Int = 1,
  @ColumnInfo(name = "encrypted_dek", typeAffinity = ColumnInfo.BLOB) val encryptedDek: ByteArray,
  @ColumnInfo(name = "dek_iv", typeAffinity = ColumnInfo.BLOB) val dekIv: ByteArray,
  @ColumnInfo(name = "dek_auth_tag", typeAffinity = ColumnInfo.BLOB) val dekAuthTag: ByteArray,
  @ColumnInfo(name = "kdf_salt", typeAffinity = ColumnInfo.BLOB) val kdfSalt: ByteArray,
  @ColumnInfo(name = "kdf_params") val kdfParams: String,
  @ColumnInfo(name = "verifier", typeAffinity = ColumnInfo.BLOB) val verifier: ByteArray,
  @ColumnInfo(name = "verifier_salt", typeAffinity = ColumnInfo.BLOB) val verifierSalt: ByteArray,
  @ColumnInfo(name = "biometric_wrapped_dek", typeAffinity = ColumnInfo.BLOB) val biometricWrappedDek: ByteArray? = null,
  @ColumnInfo(name = "biometric_iv", typeAffinity = ColumnInfo.BLOB) val biometricIv: ByteArray? = null,
  @ColumnInfo(name = "biometric_auth_tag", typeAffinity = ColumnInfo.BLOB) val biometricAuthTag: ByteArray? = null,
  @ColumnInfo(name = "biometric_enabled") val biometricEnabled: Boolean = false,
  @ColumnInfo(name = "pin_enabled") val pinEnabled: Boolean = false,
  @ColumnInfo(name = "pin_encrypted_dek", typeAffinity = ColumnInfo.BLOB) val pinEncryptedDek: ByteArray? = null,
  @ColumnInfo(name = "pin_dek_iv", typeAffinity = ColumnInfo.BLOB) val pinDekIv: ByteArray? = null,
  @ColumnInfo(name = "pin_dek_auth_tag", typeAffinity = ColumnInfo.BLOB) val pinDekAuthTag: ByteArray? = null,
  @ColumnInfo(name = "pin_kdf_salt", typeAffinity = ColumnInfo.BLOB) val pinKdfSalt: ByteArray? = null,
  @ColumnInfo(name = "pin_kdf_params") val pinKdfParams: String? = null,
  @ColumnInfo(name = "pin_verifier", typeAffinity = ColumnInfo.BLOB) val pinVerifier: ByteArray? = null,
  @ColumnInfo(name = "pin_verifier_salt", typeAffinity = ColumnInfo.BLOB) val pinVerifierSalt: ByteArray? = null,
  @ColumnInfo(name = "auto_wipe_enabled") val autoWipeEnabled: Boolean = true,
  @ColumnInfo(name = "intruder_capture_enabled") val intruderCaptureEnabled: Boolean = false,
)

@Dao
interface HistoryDao {
  @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT 500")
  fun getAllHistory(): Flow<List<HistoryItem>>

  @Query("SELECT * FROM history WHERE (:query = '' OR title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%') AND timestamp >= :minTimestamp AND timestamp <= :maxTimestamp ORDER BY timestamp DESC")
  fun getFilteredHistory(query: String, minTimestamp: Long, maxTimestamp: Long): Flow<List<HistoryItem>>

  @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT 50")
  suspend fun getRecentHistory(): List<HistoryItem>

  @Query("SELECT * FROM history WHERE url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT 15")
  suspend fun searchHistory(query: String): List<HistoryItem>

  @Query("SELECT COUNT(*) FROM history")
  suspend fun getCount(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(item: HistoryItem)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(items: List<HistoryItem>)

  @Query("DELETE FROM history")
  suspend fun clearHistory()

  @Delete
  suspend fun delete(item: HistoryItem)

  @Delete
  suspend fun deleteAll(items: List<HistoryItem>)
}

@Dao
interface BookmarkDao {
  @Query("SELECT * FROM bookmarks ORDER BY timestamp DESC")
  fun getAllBookmarks(): Flow<List<BookmarkItem>>

  @Query("SELECT * FROM bookmarks WHERE category = :folder ORDER BY timestamp DESC")
  fun getBookmarksByCategory(folder: String): Flow<List<BookmarkItem>>

  @Query("SELECT DISTINCT category FROM bookmarks")
  fun getAllCategories(): Flow<List<String>>

  @Query("SELECT * FROM bookmarks WHERE url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT 50")
  suspend fun searchBookmarks(query: String): List<BookmarkItem>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(item: BookmarkItem)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(items: List<BookmarkItem>)

  @Update
  suspend fun update(item: BookmarkItem)

  @Query("DELETE FROM bookmarks WHERE url = :url")
  suspend fun deleteByUrl(url: String)

  @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
  fun isBookmarked(url: String): Flow<Boolean>

  @Query("DELETE FROM bookmarks")
  suspend fun clearAll()

  @Delete
  suspend fun delete(item: BookmarkItem)

  @Delete
  suspend fun deleteAll(items: List<BookmarkItem>)
}

@Dao
interface BlockedEventDao {
  @Query("SELECT * FROM blocked_events ORDER BY timestamp DESC LIMIT 100")
  fun getRecentBlocked(): Flow<List<BlockedEvent>>

  @Query("SELECT COUNT(*) FROM blocked_events")
  fun getTotalBlockedCount(): Flow<Int>

  @Query("SELECT COUNT(*) FROM blocked_events")
  suspend fun getCount(): Int

  @Insert
  suspend fun insert(event: BlockedEvent)

  @Query("DELETE FROM blocked_events")
  suspend fun clearAll()
}

@Dao
interface DownloadDao {
  @Query("SELECT * FROM downloads ORDER BY timestamp DESC LIMIT 100")
  fun getAllDownloads(): Flow<List<DownloadItem>>

  @Query("SELECT COUNT(*) FROM downloads")
  suspend fun getCount(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(item: DownloadItem)

  @Query("UPDATE downloads SET status = :status WHERE downloadId = :downloadId")
  suspend fun updateStatus(downloadId: Long, status: String)

  @Delete
  suspend fun delete(item: DownloadItem)

  @Query("DELETE FROM downloads")
  suspend fun clearAll()
}

@Dao
interface SessionTabDao {
  @Query("SELECT * FROM session_tabs ORDER BY position ASC")
  fun getAllSavedTabs(): Flow<List<SessionTabEntity>>

  @Query("SELECT * FROM session_tabs ORDER BY position ASC")
  suspend fun getAllTabsList(): List<SessionTabEntity>

  @Query("SELECT * FROM session_tabs ORDER BY position ASC")
  suspend fun getAllTabs(): List<SessionTabEntity>

  @Query("SELECT COUNT(*) FROM session_tabs")
  suspend fun getCount(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(tabs: List<SessionTabEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(tab: SessionTabEntity)

  @Query("DELETE FROM session_tabs WHERE id = :id")
  suspend fun deleteById(id: String)

  @Query("DELETE FROM session_tabs WHERE profile IN ('GHOST', 'INCOGNITO')")
  suspend fun clearPrivateTabs()

  @Query("DELETE FROM session_tabs")
  suspend fun clearAllTabs()
}

@Dao
interface PasswordEntryDao {
  @Query("SELECT * FROM password_entries ORDER BY updated_at DESC")
  fun getAllEntries(): Flow<List<PasswordEntryEntity>>

  @Query("SELECT * FROM password_entries ORDER BY updated_at DESC")
  suspend fun getAllEntriesList(): List<PasswordEntryEntity>

  @Query("SELECT * FROM password_entries WHERE site_url_hash = :hash LIMIT 10")
  suspend fun getEntriesByUrlHash(hash: String): List<PasswordEntryEntity>

  @Query("SELECT * FROM password_entries WHERE id = :id LIMIT 1")
  suspend fun getEntryById(id: Long): PasswordEntryEntity?

  @Query("SELECT COUNT(*) FROM password_entries")
  suspend fun getCount(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(entry: PasswordEntryEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(entries: List<PasswordEntryEntity>)

  @Update
  suspend fun update(entry: PasswordEntryEntity)

  @Query("DELETE FROM password_entries WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("DELETE FROM password_entries")
  suspend fun clearAll()
}

@Dao
interface MasterKeyMetadataDao {
  @Query("SELECT * FROM master_key_metadata WHERE id = 1 LIMIT 1")
  suspend fun getMetadata(): MasterKeyMetadataEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveMetadata(metadata: MasterKeyMetadataEntity)

  @Query("DELETE FROM master_key_metadata")
  suspend fun clearMetadata()
}

@Dao
interface ReadingListDao {
  @Query("SELECT * FROM saved_readings ORDER BY saved_at DESC")
  fun getAllReadings(): Flow<List<ReadingListItem>>

  @Query("SELECT * FROM saved_readings WHERE folder = :folder ORDER BY saved_at DESC")
  fun getReadingsByFolder(folder: String): Flow<List<ReadingListItem>>

  @Query("SELECT DISTINCT folder FROM saved_readings ORDER BY folder ASC")
  fun getAllFolders(): Flow<List<String>>

  @Query("SELECT * FROM saved_readings WHERE title LIKE '%' || :query || '%' OR excerpt LIKE '%' || :query || '%' OR domain LIKE '%' || :query || '%' OR folder LIKE '%' || :query || '%' OR topic LIKE '%' || :query || '%' ORDER BY saved_at DESC")
  fun searchReadings(query: String): Flow<List<ReadingListItem>>

  @Query("SELECT * FROM saved_readings WHERE id = :id LIMIT 1")
  suspend fun getReadingById(id: Long): ReadingListItem?

  @Query("SELECT * FROM saved_readings WHERE url = :url LIMIT 1")
  suspend fun getReadingByUrl(url: String): ReadingListItem?

  @Query("SELECT COUNT(*) FROM saved_readings")
  fun getCountFlow(): Flow<Int>

  @Query("SELECT COUNT(*) FROM saved_readings")
  suspend fun getCount(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(item: ReadingListItem): Long

  @Update
  suspend fun update(item: ReadingListItem)

  @Delete
  suspend fun delete(item: ReadingListItem)

  @Query("DELETE FROM saved_readings WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("UPDATE saved_readings SET folder = :newFolder WHERE id = :id")
  suspend fun updateFolder(id: Long, newFolder: String)

  @Query("UPDATE saved_readings SET is_favorite = :isFavorite WHERE id = :id")
  suspend fun toggleFavorite(id: Long, isFavorite: Boolean)

  @Query("UPDATE saved_readings SET is_read = :isRead, last_read_at = :readAt WHERE id = :id")
  suspend fun updateReadStatus(id: Long, isRead: Boolean, readAt: Long = System.currentTimeMillis())

  @Query("DELETE FROM saved_readings")
  suspend fun clearAll()
}

@Database(
  entities = [
    HistoryItem::class,
    BookmarkItem::class,
    BlockedEvent::class,
    DownloadItem::class,
    ReadingListItem::class,
    SessionTabEntity::class,
    PasswordEntryEntity::class,
    MasterKeyMetadataEntity::class,
  ],
  version = 6,
  exportSchema = false,
)
abstract class RemmiDatabase : RoomDatabase() {
  sealed class DatabaseState {
    object Loading : DatabaseState()
    data class Ready(val database: RemmiDatabase) : DatabaseState()
    data class Error(val throwable: Throwable) : DatabaseState()
  }

  abstract fun historyDao(): HistoryDao
  abstract fun bookmarkDao(): BookmarkDao
  abstract fun blockedEventDao(): BlockedEventDao
  abstract fun downloadDao(): DownloadDao
  abstract fun readingListDao(): ReadingListDao
  abstract fun sessionTabDao(): SessionTabDao
  abstract fun passwordEntryDao(): PasswordEntryDao
  abstract fun masterKeyMetadataDao(): MasterKeyMetadataDao

  companion object {
    enum class WipeState {
      IDLE,
      ACTIVE,
      RECOVERY_REQUIRED
    }

    internal val dbLock = java.util.concurrent.locks.ReentrantReadWriteLock(true)

    @Volatile private var wipeState: WipeState = WipeState.IDLE

    val isWipeActive: Boolean
      get() = wipeState != WipeState.IDLE

    internal fun beginWipe() {
      synchronized(this) {
        wipeState = WipeState.ACTIVE
      }
    }

    internal fun endWipeAfterSuccess() {
      synchronized(this) {
        wipeState = WipeState.IDLE
      }
    }
    
    internal fun endWipeWithFailure() {
      synchronized(this) {
        wipeState = WipeState.RECOVERY_REQUIRED
      }
    }


    private var INSTANCE: RemmiDatabase? = null

    private const val PREFS_NAME = "remmi_vault_prefs"
    private const val KEY_ENCRYPTED_PASSPHRASE = "vault_passphrase_enc"
    private const val KEY_IV = "vault_iv"
    private const val KEY_ALIAS = "remmi_db_master_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS session_tabs (id TEXT PRIMARY KEY NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL, position INTEGER NOT NULL, timestamp INTEGER NOT NULL, profile TEXT NOT NULL, isDesktopMode INTEGER NOT NULL, isReaderMode INTEGER NOT NULL)")
      }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS password_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, site_url_hash TEXT NOT NULL, site_url_encrypted BLOB NOT NULL, username BLOB NOT NULL, password BLOB NOT NULL, notes BLOB NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, iv BLOB NOT NULL, auth_tag BLOB NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_password_entries_site_url_hash ON password_entries (site_url_hash)")
        db.execSQL("CREATE TABLE IF NOT EXISTS master_key_metadata (id INTEGER PRIMARY KEY NOT NULL, encrypted_dek BLOB NOT NULL, dek_iv BLOB NOT NULL, dek_auth_tag BLOB NOT NULL, kdf_salt BLOB NOT NULL, kdf_params TEXT NOT NULL, verifier BLOB NOT NULL, verifier_salt BLOB NOT NULL, biometric_wrapped_dek BLOB, biometric_iv BLOB, biometric_auth_tag BLOB, biometric_enabled INTEGER NOT NULL, pin_enabled INTEGER NOT NULL, pin_encrypted_dek BLOB, pin_dek_iv BLOB, pin_dek_auth_tag BLOB, pin_kdf_salt BLOB, pin_kdf_params TEXT, pin_verifier BLOB, pin_verifier_salt BLOB, auto_wipe_enabled INTEGER NOT NULL, intruder_capture_enabled INTEGER NOT NULL)")
      }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        // Schema alignment for master_key_metadata
      }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
        // Current version 5 alignment
      }
    }

    @androidx.annotation.VisibleForTesting
    var testPassphraseProvider: (() -> ByteArray)? = null

    private fun generateMasterKey(keyStore: KeyStore) {
      val keyGenerator = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        ANDROID_KEYSTORE
      )
      val spec = KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
      keyGenerator.init(spec)
      keyGenerator.generateKey()
    }

    private fun generateAndStoreNewPassphrase(prefs: android.content.SharedPreferences, secretKey: SecretKey): ByteArray {
      val rawPassphrase = ByteArray(32)
      SecureRandom().nextBytes(rawPassphrase)

      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, secretKey)
      val iv = cipher.iv
      val encryptedData = cipher.doFinal(rawPassphrase)

      prefs.edit()
        .putString(KEY_ENCRYPTED_PASSPHRASE, Base64.encodeToString(encryptedData, Base64.NO_WRAP))
        .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
        .apply()

      return rawPassphrase
    }

    private fun getOrCreatePassphrase(context: Context): ByteArray {
      testPassphraseProvider?.let { provider ->
        return provider()
      }

      val keyStore = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
      } catch (e: Throwable) {
        throw SecurityException("Android Keystore is unavailable: ${e.message}", e)
      }

      try {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
          generateMasterKey(keyStore)
        }

        val secretKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
          ?: throw VaultRecoveryRequiredException.MissingKeystoreKey("Database encryption key was invalidated (RECOVERY REQUIRED).")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encryptedPassphraseB64 = prefs.getString(KEY_ENCRYPTED_PASSPHRASE, null)
        val ivB64 = prefs.getString(KEY_IV, null)

        if (encryptedPassphraseB64 != null && ivB64 != null) {
          try {
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val encryptedData = Base64.decode(encryptedPassphraseB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            return cipher.doFinal(encryptedData)
          } catch (e: javax.crypto.AEADBadTagException) {
            android.util.Log.e("RemmiDatabase", "Passphrase decryption failed: Auth Tag mismatch.", e)
            throw VaultRecoveryRequiredException.AuthenticationTagMismatch("Database encryption key was invalidated (RECOVERY REQUIRED).", e)
          } catch (decryptionEx: Throwable) {
            android.util.Log.e("RemmiDatabase", "Passphrase decryption failed (${decryptionEx.message}).", decryptionEx)
            throw VaultRecoveryRequiredException.EncryptedPassphraseCorrupt("Database encryption key was invalidated (RECOVERY REQUIRED).", decryptionEx)
          }
        } else {
          return generateAndStoreNewPassphrase(prefs, secretKey)
        }
      } catch (e: SecurityException) {
        throw e
      } catch (e: Exception) {
        throw SecurityException("Database master encryption key derivation failed: ${e.message}", e)
      }
    }

    private val _databaseState = MutableStateFlow<DatabaseState>(DatabaseState.Loading)
    val databaseState: StateFlow<DatabaseState> = _databaseState.asStateFlow()

    @Volatile
    private var initDeferred: kotlinx.coroutines.Deferred<RemmiDatabase>? = null
    private val initMutex = Mutex()
    private val bootstrapScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun bootstrap(context: Context) {
      val existing = INSTANCE
      if (existing != null && existing.isOpen) {
        _databaseState.value = DatabaseState.Ready(existing)
        return
      }
      com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(context, com.remmi.browser.util.StartupPhase.DATABASE_BOOTSTRAP_START)
      bootstrapScope.launch {
        try {
          val db = getDatabaseAsync(context.applicationContext)
          _databaseState.value = DatabaseState.Ready(db)
        } catch (t: Throwable) {
          android.util.Log.e("RemmiDatabase", "Async DB bootstrap error: ${t.message}", t)
          _databaseState.value = DatabaseState.Error(t)
        }
      }
    }

    suspend fun getDatabaseAsync(context: Context): RemmiDatabase = withContext(Dispatchers.IO) {
      if (isWipeActive) {
        throw IllegalStateException("Cannot open database during an active Panic Wipe (state=$wipeState)")
      }
      val existing = INSTANCE
      if (existing != null && existing.isOpen) {
        if (_databaseState.value !is DatabaseState.Ready) {
          _databaseState.value = DatabaseState.Ready(existing)
        }
        return@withContext existing
      }
      val deferred = initMutex.withLock {
        var d = initDeferred
        if (d == null) {
          val ctx = context.applicationContext
          d = bootstrapScope.async(Dispatchers.IO) {
            val startTime = android.os.SystemClock.elapsedRealtime()
            try {
              com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(ctx, com.remmi.browser.util.StartupPhase.DATABASE_SQLCIPHER_OPEN_START)
              val sqlcipherLoaded = SqlCipherInitializer.ensureLoaded()
              if (!sqlcipherLoaded) {
                com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(ctx, com.remmi.browser.util.StartupPhase.DATABASE_SQLCIPHER_OPEN_FAILED)
                val err = IllegalStateException("SQLCipher native library failed to load (SQLCIPHER_LOAD_FAILED). Controlled Error state.")
                _databaseState.value = DatabaseState.Error(err)
                throw err
              }

              val passphrase = getOrCreatePassphrase(ctx)
              val supportFactory = SupportOpenHelperFactory(passphrase, null, false)
              val instance = Room.databaseBuilder(
                ctx,
                RemmiDatabase::class.java,
                "remmi_vault.db"
              )
                .openHelperFactory(supportFactory)
                .fallbackToDestructiveMigration(false)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
              INSTANCE = instance
              com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(ctx, com.remmi.browser.util.StartupPhase.DATABASE_SQLCIPHER_OPEN_OK)
              _databaseState.value = DatabaseState.Ready(instance)
              val duration = android.os.SystemClock.elapsedRealtime() - startTime
              android.util.Log.i("RemmiDatabase", "Asynchronous database initialization completed in ${duration}ms")
              instance
            } catch (t: Throwable) {
              com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(ctx, com.remmi.browser.util.StartupPhase.DATABASE_SQLCIPHER_OPEN_FAILED)
              _databaseState.value = DatabaseState.Error(t)
              throw t
            } finally {
              initDeferred = null
            }
          }
          initDeferred = d
        }
        d
      }
      try {
        deferred!!.await()
      } catch (t: Throwable) {
        throw kotlinx.coroutines.CancellationException("Database unavailable: ${t.message}").apply { initCause(t) }
      }
    }

    data class PurgeResult(
      val filesDeleted: Int,
      val filesFailed: Int,
      val keyRevoked: Boolean,
      val vaultScrubSucceeded: Boolean = false,
      val errors: List<String> = emptyList(),
    )


    fun closeDatabase() {
      synchronized(this) {
        INSTANCE?.let { db ->
          try {
            if (db.isOpen) {
              try {
                db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
              } catch (_: Exception) {}
              db.close()
            }
          } catch (_: Exception) {}
          INSTANCE = null
          _databaseState.value = DatabaseState.Loading
        }
      }
    }

        suspend fun secureWipe(
      context: Context,
      wipeVault: Boolean = false,
      vaultScrubber: suspend () -> Boolean = { true }
    ): PurgeResult {
      beginWipe()
      var vaultScrubbed = false
      var browserTablesScrubbed = true
      val errors = mutableListOf<String>()
      
      var isSuccess = false
      dbLock.writeLock().lock()
      try {

      // 1. Always scrub browser data
      INSTANCE?.let { db ->
        if (db.isOpen) {
          try {
            db.historyDao().clearHistory()
            db.sessionTabDao().clearAllTabs()
            db.downloadDao().clearAll()
            db.blockedEventDao().clearAll()
            db.readingListDao().clearAll()
          } catch (e: Exception) {
            browserTablesScrubbed = false
            errors.add("Browser tables scrub failed: ${e.message}")
          }
        }
      }
      
      // 2. Vault Scrub if requested
      if (wipeVault) {
        try {
          vaultScrubbed = vaultScrubber()
        } catch (e: Exception) {
          errors.add("Vault scrub failed: ${e.message}")
        }
      }
      
      // 3. DB Checkpoint and Close
      closeDatabase()
      
      var deleted = 0
      var failed = 0
      var keyRevoked = false

      // 4. Purge Database Files only if vault is wiped 
      // (since vault is the only thing sharing this DB, if vault is wiped, the whole DB is wiped)
      if (wipeVault) {
        val dbFile = context.getDatabasePath("remmi_vault.db")
        val dbDir = dbFile.parentFile
        if (dbFile.exists()) {
          try {
            if (dbFile.delete()) deleted++ else {
              failed++
              errors.add("Failed to delete database file: ${dbFile.name}")
            }
          } catch (e: Exception) {
            failed++
            errors.add("Exception deleting database file: ${e.message}")
          }
        }

        if (dbDir != null && dbDir.exists()) {
          dbDir.listFiles()?.forEach { file ->
            val n = file.name.lowercase()
            if (n.startsWith("remmi_vault") || n.startsWith("remmi_browser")) {
              try {
                if (file.delete()) deleted++ else {
                  failed++
                  errors.add("Failed to delete database journal/artifact: ${file.name}")
                }
              } catch (e: Exception) {
                failed++
                errors.add("Exception deleting journal file ${file.name}: ${e.message}")
              }
            }
          }
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
          val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
          if (ks.containsAlias(KEY_ALIAS)) {
            ks.deleteEntry(KEY_ALIAS)
          }
          keyRevoked = !ks.containsAlias(KEY_ALIAS)
          if (!keyRevoked) {
            errors.add("Keystore alias $KEY_ALIAS was still present after deletion.")
          }
        } catch (_: Throwable) {
          keyRevoked = true
        }
      }

      // Evaluate wipe success
      isSuccess = errors.isEmpty() && (!wipeVault || vaultScrubbed)
      return PurgeResult(
        filesDeleted = deleted,
        filesFailed = failed,
        keyRevoked = keyRevoked,
        errors = errors,
        vaultScrubSucceeded = vaultScrubbed
      )
      } catch (e: Exception) {
          errors.add("Unexpected exception during secureWipe: ${e.message}")
          return PurgeResult(0, 0, false, false, errors)
      } finally {
          dbLock.writeLock().unlock()
          if (isSuccess) {
              endWipeAfterSuccess()
          } else {
              endWipeWithFailure()
          }
      }
    }
  }
}

sealed class VaultRecoveryRequiredException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause) {
  class MissingKeystoreKey(message: String = "Missing Keystore Key", cause: Throwable? = null) : VaultRecoveryRequiredException(message, cause)
  class KeystoreOperationFailed(message: String = "Keystore Operation Failed", cause: Throwable? = null) : VaultRecoveryRequiredException(message, cause)
  class EncryptedPassphraseCorrupt(message: String = "Encrypted Passphrase Corrupt", cause: Throwable? = null) : VaultRecoveryRequiredException(message, cause)
  class AuthenticationTagMismatch(message: String = "Authentication Tag Mismatch", cause: Throwable? = null) : VaultRecoveryRequiredException(message, cause)
  class DatabaseCorrupt(message: String = "Database Corrupt", cause: Throwable? = null) : VaultRecoveryRequiredException(message, cause)
}
