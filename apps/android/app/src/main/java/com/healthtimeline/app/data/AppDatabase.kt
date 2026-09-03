package com.healthtimeline.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Database(
    entities = [
        ConditionEntity::class,
        ClinicalRecordEntity::class,
        AttachmentEntity::class,
        FollowUpScheduleEntity::class,
        FollowUpOccurrenceEntity::class,
        MedicationEntity::class,
        MedicationScheduleEntity::class,
        MedicationLogEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conditionDao(): ConditionDao
    abstract fun clinicalRecordDao(): ClinicalRecordDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun followUpDao(): FollowUpDao
    abstract fun medicationDao(): MedicationDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): AppDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = DatabaseKeyManager(context).getOrCreatePassphrase()
            val factory = SupportOpenHelperFactory(passphrase)
            val database = Room.databaseBuilder(context, AppDatabase::class.java, "health_timeline.db")
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA synchronous=FULL")
                        db.query("PRAGMA quick_check").use { cursor ->
                            check(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                                "本地数据库完整性检查失败"
                            }
                        }
                    }
                })
                .build()
            // Open eagerly so key, schema, downgrade, and integrity failures can be
            // shown as a safe startup error instead of surfacing from a UI coroutine.
            database.openHelper.writableDatabase
            return database
        }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM medication_logs WHERE id NOT IN " +
                        "(SELECT MIN(id) FROM medication_logs GROUP BY medicationId, scheduledAt)"
                )
                db.execSQL("DROP INDEX IF EXISTS index_medication_logs_medicationId_scheduleId_scheduledAt")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_medication_logs_medicationId_scheduledAt " +
                        "ON medication_logs(medicationId, scheduledAt)"
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val tables = listOf(
                    "conditions",
                    "clinical_records",
                    "attachments",
                    "follow_up_schedules",
                    "follow_up_occurrences",
                    "medications",
                    "medication_schedules",
                    "medication_logs"
                )
                tables.forEach { table ->
                    db.execSQL("ALTER TABLE $table ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
                    db.query("SELECT id FROM $table WHERE uuid = ''").use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow("id")
                        while (cursor.moveToNext()) {
                            db.execSQL(
                                "UPDATE $table SET uuid = ? WHERE id = ?",
                                arrayOf<Any>(java.util.UUID.randomUUID().toString(), cursor.getLong(idColumn))
                            )
                        }
                    }
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_${table}_uuid ON $table(uuid)")
                }
            }
        }
    }
}

private class DatabaseKeyManager(private val context: Context) {
    private val alias = "health_timeline_database_key"
    private val prefs = context.getSharedPreferences("encrypted_key_material", Context.MODE_PRIVATE)

    fun getOrCreatePassphrase(): ByteArray {
        val encrypted = prefs.getString("encrypted", null)
        val iv = prefs.getString("iv", null)
        if (encrypted != null && iv != null) {
            return cipher(Cipher.DECRYPT_MODE, Base64.decode(iv, Base64.NO_WRAP))
                .doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
        }
        check(encrypted == null && iv == null) { "数据库密钥材料不完整，已停止打开以保护现有数据" }

        val raw = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val encryptor = cipher(Cipher.ENCRYPT_MODE)
        val encryptedRaw = encryptor.doFinal(raw)
        val persisted = prefs.edit()
            .putString("encrypted", Base64.encodeToString(encryptedRaw, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(encryptor.iv, Base64.NO_WRAP))
            .commit()
        check(persisted) { "无法安全保存数据库密钥" }
        return raw
    }

    private fun cipher(mode: Int, iv: ByteArray? = null): Cipher {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = if (keyStore.containsAlias(alias)) {
            keyStore.getKey(alias, null) as SecretKey
        } else {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generator.generateKey()
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            if (iv == null) init(mode, key) else init(mode, key, GCMParameterSpec(128, iv))
        }
    }
}
