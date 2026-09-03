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
        MedicationLogEntity::class,
        FamilyMemberEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conditionDao(): ConditionDao
    abstract fun clinicalRecordDao(): ClinicalRecordDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun followUpDao(): FollowUpDao
    abstract fun medicationDao(): MedicationDao
    abstract fun familyMemberDao(): FamilyMemberDao

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val oldCounts = ROOT_AND_CHILD_TABLES.associateWith { rowCount(db, it) }
                val now = java.time.Instant.now().toString()
                val memberUuid = java.util.UUID.randomUUID().toString()

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS family_members (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, nickname TEXT NOT NULL, relationship TEXT NOT NULL, " +
                        "archived INTEGER NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, uuid TEXT NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO family_members (id, name, nickname, relationship, archived, createdAt, updatedAt, uuid) " +
                        "VALUES (1, '本人', '本人', '本人', 0, ?, ?, ?)",
                    arrayOf<Any>(now, now, memberUuid)
                )

                db.execSQL(
                    "CREATE TABLE _new_conditions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, " +
                        "color INTEGER NOT NULL, notes TEXT NOT NULL, archived INTEGER NOT NULL, createdAt TEXT NOT NULL, " +
                        "uuid TEXT NOT NULL, memberId INTEGER NOT NULL, " +
                        "FOREIGN KEY(memberId) REFERENCES family_members(id) ON UPDATE NO ACTION ON DELETE RESTRICT)"
                )
                db.execSQL(
                    "CREATE TABLE _new_clinical_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conditionId INTEGER, " +
                        "recordDate TEXT NOT NULL, title TEXT NOT NULL, stage TEXT NOT NULL, symptoms TEXT NOT NULL, " +
                        "diagnosis TEXT NOT NULL, treatment TEXT NOT NULL, medicationNotes TEXT NOT NULL, hospital TEXT NOT NULL, " +
                        "clinician TEXT NOT NULL, notes TEXT NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, uuid TEXT NOT NULL, " +
                        "memberId INTEGER NOT NULL, FOREIGN KEY(conditionId) REFERENCES _new_conditions(id) ON UPDATE NO ACTION ON DELETE SET NULL, " +
                        "FOREIGN KEY(memberId) REFERENCES family_members(id) ON UPDATE NO ACTION ON DELETE RESTRICT)"
                )
                db.execSQL(
                    "CREATE TABLE _new_attachments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, recordId INTEGER NOT NULL, " +
                        "kind TEXT NOT NULL, displayName TEXT NOT NULL, mimeType TEXT NOT NULL, relativePath TEXT NOT NULL, " +
                        "sizeBytes INTEGER NOT NULL, sha256 TEXT NOT NULL, createdAt TEXT NOT NULL, uuid TEXT NOT NULL, " +
                        "FOREIGN KEY(recordId) REFERENCES _new_clinical_records(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE TABLE _new_follow_up_schedules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conditionId INTEGER, " +
                        "title TEXT NOT NULL, recurrenceType TEXT NOT NULL, interval INTEGER NOT NULL, anchorDate TEXT NOT NULL, " +
                        "anchorDayOfMonth INTEGER NOT NULL, weekday INTEGER, reminderTime TEXT NOT NULL, leadDays INTEGER NOT NULL, " +
                        "nextDueDate TEXT NOT NULL, enabled INTEGER NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, uuid TEXT NOT NULL, " +
                        "memberId INTEGER NOT NULL, FOREIGN KEY(conditionId) REFERENCES _new_conditions(id) ON UPDATE NO ACTION ON DELETE SET NULL, " +
                        "FOREIGN KEY(memberId) REFERENCES family_members(id) ON UPDATE NO ACTION ON DELETE RESTRICT)"
                )
                db.execSQL(
                    "CREATE TABLE _new_follow_up_occurrences (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, scheduleId INTEGER NOT NULL, " +
                        "dueDate TEXT NOT NULL, status TEXT NOT NULL, completedAt TEXT, createdAt TEXT NOT NULL, uuid TEXT NOT NULL, " +
                        "FOREIGN KEY(scheduleId) REFERENCES _new_follow_up_schedules(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE TABLE _new_medications (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conditionId INTEGER, name TEXT NOT NULL, " +
                        "doseAmount TEXT NOT NULL, doseUnit TEXT NOT NULL, instructions TEXT NOT NULL, startDate TEXT NOT NULL, endDate TEXT, " +
                        "mode TEXT NOT NULL, archived INTEGER NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, uuid TEXT NOT NULL, " +
                        "memberId INTEGER NOT NULL, FOREIGN KEY(conditionId) REFERENCES _new_conditions(id) ON UPDATE NO ACTION ON DELETE SET NULL, " +
                        "FOREIGN KEY(memberId) REFERENCES family_members(id) ON UPDATE NO ACTION ON DELETE RESTRICT)"
                )
                db.execSQL(
                    "CREATE TABLE _new_medication_schedules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, medicationId INTEGER NOT NULL, " +
                        "localTime TEXT NOT NULL, enabled INTEGER NOT NULL, uuid TEXT NOT NULL, " +
                        "FOREIGN KEY(medicationId) REFERENCES _new_medications(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE TABLE _new_medication_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, medicationId INTEGER NOT NULL, " +
                        "scheduleId INTEGER, scheduledAt TEXT NOT NULL, actualAt TEXT, status TEXT NOT NULL, doseAmountSnapshot TEXT NOT NULL, " +
                        "doseUnitSnapshot TEXT NOT NULL, createdAt TEXT NOT NULL, uuid TEXT NOT NULL, " +
                        "FOREIGN KEY(medicationId) REFERENCES _new_medications(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )

                db.execSQL("INSERT INTO _new_conditions SELECT id,name,color,notes,archived,createdAt,uuid,1 FROM conditions")
                db.execSQL("INSERT INTO _new_clinical_records SELECT id,conditionId,recordDate,title,stage,symptoms,diagnosis,treatment,medicationNotes,hospital,clinician,notes,createdAt,updatedAt,uuid,1 FROM clinical_records")
                db.execSQL("INSERT INTO _new_attachments SELECT * FROM attachments")
                db.execSQL("INSERT INTO _new_follow_up_schedules SELECT id,conditionId,title,recurrenceType,interval,anchorDate,anchorDayOfMonth,weekday,reminderTime,leadDays,nextDueDate,enabled,createdAt,updatedAt,uuid,1 FROM follow_up_schedules")
                db.execSQL("INSERT INTO _new_follow_up_occurrences SELECT * FROM follow_up_occurrences")
                db.execSQL("INSERT INTO _new_medications SELECT id,conditionId,name,doseAmount,doseUnit,instructions,startDate,endDate,mode,archived,createdAt,updatedAt,uuid,1 FROM medications")
                db.execSQL("INSERT INTO _new_medication_schedules SELECT * FROM medication_schedules")
                db.execSQL("INSERT INTO _new_medication_logs SELECT * FROM medication_logs")

                NEW_TABLES.forEach { (oldName, newName) ->
                    check(rowCount(db, oldName) == rowCount(db, newName)) { "迁移前后记录数量不一致：$oldName" }
                }

                listOf(
                    "attachments", "follow_up_occurrences", "medication_logs", "medication_schedules",
                    "clinical_records", "follow_up_schedules", "medications", "conditions"
                ).forEach { db.execSQL("DROP TABLE $it") }
                listOf(
                    "conditions", "clinical_records", "attachments", "follow_up_schedules",
                    "follow_up_occurrences", "medications", "medication_schedules", "medication_logs"
                ).forEach { db.execSQL("ALTER TABLE _new_$it RENAME TO $it") }

                INDEX_SQL.forEach(db::execSQL)
                check(rowCount(db, "family_members") == 1L) { "默认家庭成员创建失败" }
                oldCounts.forEach { (table, count) ->
                    check(rowCount(db, table) == count) { "迁移后记录数量不一致：$table" }
                }
                db.query("PRAGMA foreign_key_check").use { cursor ->
                    check(!cursor.moveToFirst()) { "迁移后数据引用关系异常" }
                }
            }
        }

        private val ROOT_AND_CHILD_TABLES = listOf(
            "conditions", "clinical_records", "attachments", "follow_up_schedules",
            "follow_up_occurrences", "medications", "medication_schedules", "medication_logs"
        )
        private val NEW_TABLES = ROOT_AND_CHILD_TABLES.associateWith { "_new_$it" }
        private val INDEX_SQL = listOf(
            "CREATE UNIQUE INDEX index_family_members_uuid ON family_members(uuid)",
            "CREATE INDEX index_family_members_archived ON family_members(archived)",
            "CREATE INDEX index_conditions_memberId ON conditions(memberId)",
            "CREATE UNIQUE INDEX index_conditions_uuid ON conditions(uuid)",
            "CREATE INDEX index_clinical_records_conditionId ON clinical_records(conditionId)",
            "CREATE INDEX index_clinical_records_memberId ON clinical_records(memberId)",
            "CREATE INDEX index_clinical_records_recordDate ON clinical_records(recordDate)",
            "CREATE UNIQUE INDEX index_clinical_records_uuid ON clinical_records(uuid)",
            "CREATE INDEX index_attachments_recordId ON attachments(recordId)",
            "CREATE UNIQUE INDEX index_attachments_uuid ON attachments(uuid)",
            "CREATE INDEX index_follow_up_schedules_conditionId ON follow_up_schedules(conditionId)",
            "CREATE INDEX index_follow_up_schedules_memberId ON follow_up_schedules(memberId)",
            "CREATE INDEX index_follow_up_schedules_nextDueDate ON follow_up_schedules(nextDueDate)",
            "CREATE UNIQUE INDEX index_follow_up_schedules_uuid ON follow_up_schedules(uuid)",
            "CREATE INDEX index_follow_up_occurrences_scheduleId ON follow_up_occurrences(scheduleId)",
            "CREATE UNIQUE INDEX index_follow_up_occurrences_scheduleId_dueDate ON follow_up_occurrences(scheduleId,dueDate)",
            "CREATE UNIQUE INDEX index_follow_up_occurrences_uuid ON follow_up_occurrences(uuid)",
            "CREATE INDEX index_medications_conditionId ON medications(conditionId)",
            "CREATE INDEX index_medications_memberId ON medications(memberId)",
            "CREATE INDEX index_medications_startDate ON medications(startDate)",
            "CREATE UNIQUE INDEX index_medications_uuid ON medications(uuid)",
            "CREATE INDEX index_medication_schedules_medicationId ON medication_schedules(medicationId)",
            "CREATE UNIQUE INDEX index_medication_schedules_uuid ON medication_schedules(uuid)",
            "CREATE INDEX index_medication_logs_medicationId ON medication_logs(medicationId)",
            "CREATE UNIQUE INDEX index_medication_logs_medicationId_scheduledAt ON medication_logs(medicationId,scheduledAt)",
            "CREATE UNIQUE INDEX index_medication_logs_uuid ON medication_logs(uuid)"
        )

        private fun rowCount(db: SupportSQLiteDatabase, table: String): Long =
            db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
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
