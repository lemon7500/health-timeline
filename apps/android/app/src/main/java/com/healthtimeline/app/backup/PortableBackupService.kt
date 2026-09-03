package com.healthtimeline.app.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.healthtimeline.app.data.AttachmentEntity
import com.healthtimeline.app.data.AttachmentStore
import com.healthtimeline.app.data.HealthRepository
import com.healthtimeline.app.data.FamilyMemberEntity
import com.healthtimeline.shared.BACKUP_SCHEMA_VERSION
import com.healthtimeline.shared.BackupImportPreview
import com.healthtimeline.shared.MergeChoice
import com.healthtimeline.shared.MergePlanner
import com.healthtimeline.shared.PortableAttachment
import com.healthtimeline.shared.PortableJson
import com.healthtimeline.shared.PortableSnapshot
import com.healthtimeline.shared.PortableFamilyMember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class PortableBackupService(
    private val context: Context,
    private val repository: HealthRepository,
    private val legacy: BackupService = BackupService(context, repository)
) {
    private val database = repository.database

    suspend fun export(destination: Uri, password: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            repository.mutationMutex.withLock {
                runCatching {
                    require(password.size >= 8) { "备份密码至少需要 8 位" }
                    val zip = File.createTempFile("health-v3-", ".zip", context.cacheDir)
                    val encrypted = File.createTempFile("health-v3-", ".encrypted", context.cacheDir)
                    try {
                        val entities = loadEntities()
                        val portable = PortableSnapshotMapper.toPortable(
                            entities,
                            InstallationIdentity.getOrCreate(context),
                            Instant.now().toString()
                        )
                        writeZip(zip, portable, entities.attachments)
                        FileInputStream(zip).use { input ->
                            FileOutputStream(encrypted).use { output ->
                                BackupCrypto.encrypt(input, output, password)
                                output.fd.sync()
                            }
                        }
                        writeDestination(destination, encrypted)
                    } finally {
                        zip.delete(); encrypted.delete()
                    }
                }
            }
        } finally { password.fill('\u0000') }
    }

    suspend fun previewImport(source: Uri, password: CharArray, targetMemberId: Long?): Result<BackupImportPreview> = withContext(Dispatchers.IO) {
        try {
            repository.mutationMutex.withLock {
                runCatching {
                    prepare(source, password).use { prepared ->
                        if (prepared.portable == null) {
                            BackupImportPreview(0, 0, 0, emptyList(), legacyReplacementOnly = true)
                        } else {
                            val current = loadEntities()
                            val local = PortableSnapshotMapper.toPortable(
                                current, InstallationIdentity.getOrCreate(context), Instant.now().toString()
                            )
                            val imported = normalizeForFamily(
                                prepared.portable,
                                current.members.firstOrNull { it.id == targetMemberId },
                                createDefaultMember = false
                            )
                            MergePlanner.preview(local, imported)
                        }
                    }
                }
            }
        } finally { password.fill('\u0000') }
    }

    suspend fun mergeImport(
        source: Uri,
        password: CharArray,
        decisions: Map<String, MergeChoice>,
        targetMemberId: Long?
    ): Result<BackupImportPreview> = withContext(Dispatchers.IO) {
        try {
            repository.mutationMutex.withLock {
                runCatching {
                    prepare(source, password).use { prepared ->
                        val rawImported = requireNotNull(prepared.portable) { "旧版备份不能与现有数据自动合并，请选择安全替换" }
                        val currentEntities = loadEntities()
                        val imported = normalizeForFamily(
                            rawImported,
                            currentEntities.members.firstOrNull { it.id == targetMemberId },
                            createDefaultMember = false
                        )
                        val local = PortableSnapshotMapper.toPortable(
                            currentEntities, InstallationIdentity.getOrCreate(context), Instant.now().toString()
                        )
                        val preview = MergePlanner.preview(local, imported)
                        val merged = MergePlanner.merge(local, imported, decisions)
                        installPortable(merged, imported, currentEntities, prepared.root, decisions)
                        preview
                    }
                }
            }
        } finally { password.fill('\u0000') }
    }

    suspend fun replaceFromBackup(source: Uri, password: CharArray, targetMemberId: Long?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            runCatching {
                // Determine the manifest version only after authenticating and validating the archive.
                prepare(source, password).use { prepared ->
                    if (prepared.portable == null) {
                        val target = repository.mutationMutex.withLock {
                            val current = loadEntities()
                            writeSafetyBackup(password, current)
                            current.members.firstOrNull { it.id == targetMemberId && !it.archived }
                                ?: current.members.firstOrNull { !it.archived }
                                ?: error("没有可用的家庭成员")
                        }
                        legacy.restore(source, password.copyOf(), target.id).getOrThrow()
                    } else {
                        repository.mutationMutex.withLock {
                            val current = loadEntities()
                            val imported = normalizeForFamily(prepared.portable, null, createDefaultMember = true)
                            writeSafetyBackup(password, current)
                            installPortable(
                                imported,
                                imported,
                                current,
                                prepared.root,
                                imported.allConflictKeys().associateWith { MergeChoice.USE_IMPORTED }
                            )
                        }
                    }
                }
            }
        } finally { password.fill('\u0000') }
    }

    suspend fun importLegacyAsNew(source: Uri, password: CharArray, targetMemberId: Long?): Result<Int> {
        val target = targetMemberId ?: run {
            password.fill('\u0000')
            return Result.failure(IllegalArgumentException("请选择旧版备份要导入到的家庭成员"))
        }
        return legacy.importAsNew(source, password, target)
    }

    private suspend fun loadEntities() = database.withTransaction {
        BackupSnapshot(
            database.conditionDao().all(), database.clinicalRecordDao().all(), database.attachmentDao().all(),
            database.followUpDao().allSchedules(), database.followUpDao().allOccurrences(),
            database.medicationDao().allMedications(), database.medicationDao().allSchedules(),
            database.medicationDao().allLogs(), database.familyMemberDao().all()
        )
    }

    private fun writeZip(target: File, portable: PortableSnapshot, attachments: List<AttachmentEntity>) {
        val files = attachments.associateBy { it.uuid }
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(PortableJson.encode(portable).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            portable.attachments.forEach { attachment ->
                val entity = requireNotNull(files[attachment.uuid]) { "附件索引缺失：${attachment.displayName}" }
                val file = File(context.filesDir, entity.relativePath)
                require(file.isFile) { "附件缺失：${attachment.displayName}" }
                require(file.length() == attachment.sizeBytes && sha256(file) == attachment.sha256) { "附件校验失败：${attachment.displayName}" }
                zip.putNextEntry(ZipEntry(attachment.archivePath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private suspend fun writeSafetyBackup(password: CharArray) = writeSafetyBackup(password, loadEntities())

    private fun writeSafetyBackup(password: CharArray, entities: BackupSnapshot) {
        val directory = File(context.filesDir, "safety_backups")
        require(directory.isDirectory || directory.mkdirs()) { "无法创建自动安全备份目录" }
        val stamp = System.currentTimeMillis()
        val zip = File.createTempFile("safety-", ".zip", context.cacheDir)
        val temporary = File(directory, ".before-replace-$stamp.tmp")
        val destination = File(directory, "before-replace-$stamp.htbackup")
        try {
            val portable = PortableSnapshotMapper.toPortable(
                entities, InstallationIdentity.getOrCreate(context), Instant.now().toString()
            )
            writeZip(zip, portable, entities.attachments)
            zip.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    BackupCrypto.encrypt(input, output, password)
                    output.fd.sync()
                }
            }
            require(temporary.renameTo(destination)) { "无法保存自动安全备份" }
            directory.listFiles { file -> file.extension == "htbackup" }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(3)
                ?.forEach { it.delete() }
        } finally {
            zip.delete(); temporary.delete()
        }
    }

    private fun prepare(source: Uri, password: CharArray): PreparedImport {
        val root = File(context.cacheDir, "portable-restore-${UUID.randomUUID()}").apply {
            require(mkdirs()) { "无法创建恢复临时目录" }
        }
        val zipFile = File(root, "backup.zip")
        try {
            try {
                context.contentResolver.openInputStream(source)?.use { input ->
                    FileOutputStream(zipFile).use { output ->
                        BackupCrypto.decrypt(input, output, password)
                        output.fd.sync()
                    }
                } ?: error("无法读取备份文件")
            } catch (error: IllegalArgumentException) {
                throw error
            } catch (error: Throwable) {
                throw IllegalArgumentException("密码错误或备份文件已损坏", error)
            }
            ZipFile(zipFile).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json") ?: error("备份缺少清单")
                require(manifestEntry.size in 1..10_000_000) { "备份清单大小异常" }
                val json = zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
                if (JSONObject(json).optInt("schemaVersion", -1) == 1) {
                    // The legacy service performs its own complete validation before replacement.
                    return PreparedImport(root, null)
                }
                val portable = PortableJson.decode(json)
                val totalSize = portable.attachments.fold(0L) { total, item -> Math.addExact(total, item.sizeBytes) }
                require(context.filesDir.usableSpace > totalSize + 20L * 1024L * 1024L) { "手机存储空间不足" }
                val staged = File(root, "files").apply { require(mkdirs()) { "无法创建附件临时目录" } }
                portable.attachments.forEach { attachment ->
                    val entry = zip.getEntry(attachment.archivePath) ?: error("附件缺失：${attachment.displayName}")
                    require(entry.size in 0..AttachmentStore.MAX_FILE_BYTES) { "附件大小异常：${attachment.displayName}" }
                    val destination = File(staged, attachment.uuid)
                    copyVerified(zip, entry.name, destination, attachment)
                }
                return PreparedImport(root, portable)
            }
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }
    }

    private fun copyVerified(zip: ZipFile, entryName: String, destination: File, attachment: PortableAttachment) {
        val entry = requireNotNull(zip.getEntry(entryName))
        zip.getInputStream(entry).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied = Math.addExact(copied, read.toLong())
                    require(copied <= attachment.sizeBytes && copied <= AttachmentStore.MAX_FILE_BYTES) { "附件大小校验失败：${attachment.displayName}" }
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        require(destination.length() == attachment.sizeBytes && sha256(destination) == attachment.sha256) { "附件校验失败：${attachment.displayName}" }
    }

    private suspend fun installPortable(
        merged: PortableSnapshot,
        imported: PortableSnapshot,
        current: BackupSnapshot,
        preparedRoot: File,
        decisions: Map<String, MergeChoice>
    ) {
        val importedById = imported.attachments.associateBy { it.uuid }
        val localById = current.attachments.associateBy { it.uuid }
        val installId = UUID.randomUUID().toString()
        val relativeRoot = "restored_attachments/$installId"
        val installed = File(context.filesDir, relativeRoot)
        require(installed.mkdirs()) { "无法创建附件恢复目录" }
        val importedPaths = hashMapOf<String, String>()
        try {
            merged.attachments.forEach { attachment ->
                val incoming = importedById[attachment.uuid]
                val local = localById[attachment.uuid]
                val usesIncoming = incoming != null && (local == null || decisions["attachment:${attachment.uuid}"] == MergeChoice.USE_IMPORTED ||
                    (local.sha256 == incoming.sha256 && local.sizeBytes == incoming.sizeBytes))
                if (usesIncoming && (local == null || local.sha256 != attachment.sha256 || !File(context.filesDir, local.relativePath).isFile)) {
                    val source = File(preparedRoot, "files/${attachment.uuid}")
                    require(source.isFile) { "附件临时文件缺失：${attachment.displayName}" }
                    val destination = File(installed, attachment.uuid)
                    source.inputStream().use { input ->
                        FileOutputStream(destination).use { output -> input.copyTo(output); output.fd.sync() }
                    }
                    importedPaths[attachment.uuid] = "$relativeRoot/${attachment.uuid}"
                }
            }
            val entities = PortableSnapshotMapper.toEntities(merged, current) { attachment, local ->
                importedPaths[attachment.uuid] ?: requireNotNull(local?.relativePath) { "附件没有可用的本地文件：${attachment.displayName}" }
            }
            replaceDatabase(entities)
            val livePaths = entities.attachments.mapTo(hashSetOf()) { it.relativePath }
            current.attachments.filter { it.relativePath !in livePaths }.forEach { old ->
                runCatching { File(context.filesDir, old.relativePath).delete() }
            }
            if (importedPaths.isEmpty()) installed.deleteRecursively()
        } catch (error: Throwable) {
            installed.deleteRecursively()
            throw error
        }
    }

    private suspend fun replaceDatabase(snapshot: BackupSnapshot) {
        database.withTransaction {
            database.medicationDao().clearLogs(); database.medicationDao().clearSchedules(); database.medicationDao().clearMedications()
            database.followUpDao().clearOccurrences(); database.followUpDao().clearSchedules()
            database.attachmentDao().clear(); database.clinicalRecordDao().clear(); database.conditionDao().clear()
            database.familyMemberDao().clear()
            database.familyMemberDao().insertAll(snapshot.members)
            database.conditionDao().insertAll(snapshot.conditions)
            database.clinicalRecordDao().insertAll(snapshot.records)
            database.attachmentDao().insertAll(snapshot.attachments)
            database.followUpDao().insertSchedules(snapshot.followUps)
            database.followUpDao().insertOccurrences(snapshot.occurrences)
            database.medicationDao().insertMedications(snapshot.medications)
            database.medicationDao().insertSchedules(snapshot.medicationSchedules)
            database.medicationDao().insertLogs(snapshot.medicationLogs)
        }
    }

    private fun normalizeForFamily(
        value: PortableSnapshot,
        targetMember: FamilyMemberEntity?,
        createDefaultMember: Boolean
    ): PortableSnapshot {
        if (value.schemaVersion == BACKUP_SCHEMA_VERSION) return value
        require(targetMember != null || createDefaultMember) { "请选择旧版备份要导入到的家庭成员" }
        val member = targetMember?.let {
            PortableFamilyMember(
                it.uuid, it.name, it.nickname, it.relationship, it.archived,
                it.createdAt, it.updatedAt
            )
        } ?: PortableFamilyMember(
            uuid = UUID.nameUUIDFromBytes("health-timeline-v2:${value.sourceInstallationId}".toByteArray()).toString(),
            name = "本人",
            nickname = "本人",
            relationship = "本人",
            archived = false,
            createdAt = value.exportedAt,
            updatedAt = value.exportedAt
        )
        return value.copy(
            schemaVersion = BACKUP_SCHEMA_VERSION,
            members = listOf(member),
            conditions = value.conditions.map { it.copy(memberUuid = member.uuid) },
            records = value.records.map { it.copy(memberUuid = member.uuid) },
            followUps = value.followUps.map { it.copy(memberUuid = member.uuid) },
            medications = value.medications.map { it.copy(memberUuid = member.uuid) }
        ).also(com.healthtimeline.shared.PortableSnapshotValidator::validate)
    }

    private fun writeDestination(destination: Uri, encrypted: File) {
        val descriptor = context.contentResolver.openFileDescriptor(destination, "rwt") ?: error("无法创建备份文件")
        descriptor.use {
            FileOutputStream(it.fileDescriptor).use { output ->
                encrypted.inputStream().use { input -> input.copyTo(output) }
                output.fd.sync()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class PreparedImport(val root: File, val portable: PortableSnapshot?) : AutoCloseable {
        override fun close() { root.deleteRecursively() }
    }
}

private object InstallationIdentity {
    fun getOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences("installation_identity", Context.MODE_PRIVATE)
        prefs.getString("uuid", null)?.let { return it }
        val value = UUID.randomUUID().toString()
        check(prefs.edit().putString("uuid", value).commit()) { "无法保存设备安装标识" }
        return value
    }
}

private fun PortableSnapshot.allConflictKeys(): List<String> = buildList {
    members.forEach { add("member:${it.uuid}") }
    conditions.forEach { add("condition:${it.uuid}") }
    records.forEach { add("record:${it.uuid}") }
    attachments.forEach { add("attachment:${it.uuid}") }
    followUps.forEach { add("followUp:${it.uuid}") }
    occurrences.forEach { add("occurrence:${it.uuid}") }
    medications.forEach { add("medication:${it.uuid}") }
    medicationSchedules.forEach { add("medicationSchedule:${it.uuid}") }
    medicationLogs.forEach { add("medicationLog:${it.uuid}") }
}
