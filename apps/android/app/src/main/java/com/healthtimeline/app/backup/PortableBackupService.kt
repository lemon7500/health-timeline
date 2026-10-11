package com.healthtimeline.app.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.healthtimeline.app.data.AttachmentEntity
import com.healthtimeline.app.data.AttachmentStore
import com.healthtimeline.app.data.HealthRepository
import com.healthtimeline.app.data.FamilyMemberEntity
import com.healthtimeline.app.data.SafetyStatusStore
import com.healthtimeline.shared.BACKUP_SCHEMA_VERSION
import com.healthtimeline.shared.FAMILY_BACKUP_SCHEMA_VERSION
import com.healthtimeline.shared.PREVIOUS_BACKUP_SCHEMA_VERSION
import com.healthtimeline.shared.BackupImportPreview
import com.healthtimeline.shared.MergeChoice
import com.healthtimeline.shared.MergePlanner
import com.healthtimeline.shared.PortableAttachment
import com.healthtimeline.shared.PortableJson
import com.healthtimeline.shared.PortableSnapshot
import com.healthtimeline.shared.PortableFamilyMember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileDescriptor
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PortableBackupService(
    private val context: Context,
    private val repository: HealthRepository,
    private val legacy: BackupService = BackupService(context, repository),
    private val safetyStatusStore: SafetyStatusStore = SafetyStatusStore(context),
    private val availableStorageBytes: () -> Long = { minOf(context.cacheDir.usableSpace, context.filesDir.usableSpace) },
    private val availableDestinationStorageBytes: (FileDescriptor) -> Long? = BackupDestinationStorage::availableBytes
) {
    private val database = repository.database

    suspend fun export(destination: Uri, password: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            repository.mutationMutex.withLock {
                runBackupCatching {
                    require(password.size >= 8) { "备份密码至少需要 8 位" }
                    val zip = File.createTempFile("health-v5-", ".zip", context.cacheDir)
                    val encrypted = File.createTempFile("health-v5-", ".encrypted", context.cacheDir)
                    try {
                        val entities = loadEntities()
                        val budget = resourceBudget()
                        val portable = PortableSnapshotMapper.toPortable(
                            entities,
                            InstallationIdentity.getOrCreate(context),
                            Instant.now().toString()
                        )
                        writeZip(zip, portable, entities.attachments, budget)
                        FileInputStream(zip).use { input ->
                            FileOutputStream(encrypted).use { output ->
                                BackupCrypto.encrypt(input, output, password, budget)
                                output.fd.sync()
                            }
                        }
                        writeDestination(destination, encrypted, budget)
                        safetyStatusStore.recordSuccessfulBackup()
                    } finally {
                        zip.delete(); encrypted.delete()
                    }
                }
            }
        } finally { password.fill('\u0000') }
    }

    suspend fun previewImport(source: Uri, password: CharArray, targetMemberId: Long?,
        expectedTargetMemberUuid: String? = null): Result<BackupImportPreview> = withContext(Dispatchers.IO) {
        try {
            repository.mutationMutex.withLock {
                runBackupCatching {
                    requireExpectedTargetMember(targetMemberId, expectedTargetMemberUuid)
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
        targetMemberId: Long?,
        expectedTargetMemberUuid: String? = null
    ): Result<BackupImportPreview> = withContext(Dispatchers.IO) {
        try {
            repository.mutationMutex.withLock {
                runBackupCatching {
                    requireExpectedTargetMember(targetMemberId, expectedTargetMemberUuid)
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
                        installPortable(merged, imported, currentEntities, prepared.root)
                        preview
                    }
                }
            }
        } finally { password.fill('\u0000') }
    }

    suspend fun replaceFromBackup(source: Uri, password: CharArray, targetMemberId: Long?,
        expectedTargetMemberUuid: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            runBackupCatching {
                // Determine the manifest version only after authenticating and validating the archive.
                prepare(source, password).use { prepared ->
                    if (prepared.portable == null) {
                        val target = repository.mutationMutex.withLock {
                            requireExpectedTargetMember(targetMemberId, expectedTargetMemberUuid)
                            val current = loadEntities()
                            current.members.firstOrNull { it.id == targetMemberId && !it.archived }
                                ?: current.members.firstOrNull { !it.archived }
                                ?: error("没有可用的家庭成员")
                        }
                        val legacyPassword = password.copyOf()
                        try {
                            legacy.restore(source, legacyPassword, target.id,
                                expectedTargetMemberUuid = target.uuid,
                                beforeReplace = { writeSafetyBackup(password, loadEntities()) }).getOrThrow()
                        } finally { legacyPassword.fill('\u0000') }
                    } else {
                        repository.mutationMutex.withLock {
                            requireExpectedTargetMember(targetMemberId, expectedTargetMemberUuid)
                            val current = loadEntities()
                            val imported = normalizeForFamily(prepared.portable, null, createDefaultMember = true)
                            writeSafetyBackup(password, current)
                            installPortable(
                                imported,
                                imported,
                                current,
                                prepared.root
                            )
                        }
                    }
                }
            }
        } finally { password.fill('\u0000') }
    }

    suspend fun importLegacyAsNew(source: Uri, password: CharArray, targetMemberId: Long?,
        expectedTargetMemberUuid: String? = null): Result<Int> {
        val target = targetMemberId ?: run {
            password.fill('\u0000')
            return Result.failure(IllegalArgumentException("请选择旧版备份要导入到的家庭成员"))
        }
        return legacy.importAsNew(source, password, target, expectedTargetMemberUuid)
    }

    /** Called only while the repository mutation mutex is held. A reused ID is not the same person. */
    private suspend fun requireExpectedTargetMember(id: Long?, expectedUuid: String?) {
        if (expectedUuid == null) return
        val member = id?.let { database.familyMemberDao().byId(it) }
        require(member != null && !member.archived && member.uuid.equals(expectedUuid, ignoreCase = true)) {
            "导入时选择的家庭成员已改变或归档，请重新选择并核对"
        }
    }

    private suspend fun loadEntities() = database.withTransaction {
        BackupSnapshot(
            database.conditionDao().all(), database.clinicalRecordDao().all(), database.attachmentDao().all(),
            database.followUpDao().allSchedules(), database.followUpDao().allOccurrences(),
            database.medicationDao().allMedications(), database.medicationDao().allSchedules(),
            database.medicationDao().allLogs(), database.familyMemberDao().all()
        )
    }

    private suspend fun resourceBudget(): BackupResourceBudget {
        val coroutineContext = currentCoroutineContext()
        return BackupResourceBudget(availableStorageBytes, { coroutineContext.ensureActive() })
    }

    private fun writeZip(target: File, portable: PortableSnapshot, attachments: List<AttachmentEntity>, budget: BackupResourceBudget) {
        val files = attachments.associateBy { it.uuid.lowercase() }
        val manifest = PortableJson.encode(portable).toByteArray(Charsets.UTF_8)
        require(manifest.size.toLong() <= BackupResourceBudget.MAX_MANIFEST_BYTES) { "备份清单超过可恢复的大小，请分批整理资料" }
        FileOutputStream(target).use { fileOutput ->
        ZipOutputStream(budget.limitOutput(fileOutput)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
            portable.attachments.forEach { attachment ->
                val entity = requireNotNull(files[attachment.uuid]) { "附件索引缺失：${attachment.displayName}" }
                val file = repository.attachmentStore.file(entity)
                require(file.isFile) { "附件缺失：${attachment.displayName}" }
                require(file.length() == attachment.sizeBytes && sha256(file, budget) == attachment.sha256) { "附件校验失败：${attachment.displayName}" }
                zip.putNextEntry(ZipEntry(attachment.archivePath))
                file.inputStream().use { budget.copy(it, zip, attachment.sizeBytes) }
                zip.closeEntry()
            }
        }
        }
    }

    private suspend fun writeSafetyBackup(password: CharArray) = writeSafetyBackup(password, loadEntities())

    private suspend fun writeSafetyBackup(password: CharArray, entities: BackupSnapshot) {
        val directory = File(context.filesDir, "safety_backups")
        require(directory.isDirectory || directory.mkdirs()) { "无法创建自动安全备份目录" }
        val stamp = System.currentTimeMillis()
        val zip = File.createTempFile("safety-", ".zip", context.cacheDir)
        val temporary = File(directory, ".before-replace-$stamp.tmp")
        val destination = File(directory, "before-replace-$stamp.htbackup")
        try {
            val budget = resourceBudget()
            val portable = PortableSnapshotMapper.toPortable(
                entities, InstallationIdentity.getOrCreate(context), Instant.now().toString()
            )
            writeZip(zip, portable, entities.attachments, budget)
            zip.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    BackupCrypto.encrypt(input, output, password, budget)
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

    private suspend fun prepare(source: Uri, password: CharArray): PreparedImport {
        val budget = resourceBudget()
        val root = File(context.cacheDir, "portable-restore-${UUID.randomUUID()}").apply {
            require(mkdirs()) { "无法创建恢复临时目录" }
        }
        val zipFile = File(root, "backup.zip")
        try {
            try {
                context.contentResolver.openInputStream(source)?.use { input ->
                    FileOutputStream(zipFile).use { output ->
                        BackupCrypto.decrypt(input, output, password, budget)
                        output.fd.sync()
                    }
                } ?: error("无法读取备份文件")
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalArgumentException) {
                throw error
            } catch (error: Throwable) {
                throw IllegalArgumentException("密码错误或备份文件已损坏", error)
            }
            val zip = BoundedBackupZip.open(zipFile, budget, availableStorageBytes())
            val json = zip.readManifest()
            if (JSONObject(json).optInt("schemaVersion", -1) == 1) {
                // The legacy service performs its own complete validation before replacement.
                return PreparedImport(root, null)
            }
            val portable = PortableJson.decode(json)
            val totalSize = portable.attachments.fold(0L) { total, item -> Math.addExact(total, item.sizeBytes) }
            budget.requireSpace(totalSize)
            val staged = File(root, "files").apply { require(mkdirs()) { "无法创建附件临时目录" } }
            val targets = portable.attachments.associate { attachment ->
                val destination = File(staged, attachment.uuid)
                attachment.archivePath to BoundedBackupZip.Target(destination, attachment.sizeBytes, attachment.sha256)
            }
            zip.extract(targets)
            return PreparedImport(root, portable)
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }
    }

    private suspend fun installPortable(
        merged: PortableSnapshot,
        imported: PortableSnapshot,
        current: BackupSnapshot,
        preparedRoot: File
    ) {
        val budget = resourceBudget()
        val importedById = imported.attachments.associateBy { it.uuid }
        val localById = current.attachments.associateBy { it.uuid.lowercase() }
        val installId = UUID.randomUUID().toString()
        val relativeRoot = "restored_attachments/$installId"
        val installed = File(context.filesDir, relativeRoot)
        require(installed.mkdirs()) { "无法创建附件恢复目录" }
        val importedPaths = hashMapOf<String, String>()
        var databaseCommitted = false
        try {
            merged.attachments.forEach { attachment ->
                val incoming = importedById[attachment.uuid]
                val local = localById[attachment.uuid]
                // The merge choice controls metadata. Bytes with the chosen hash are
                // interchangeable, so a verified backup can repair a corrupt local copy.
                val usesIncoming = incoming != null && incoming.sha256 == attachment.sha256 &&
                    incoming.sizeBytes == attachment.sizeBytes
                val localVerified = local != null && local.sha256 == attachment.sha256 &&
                    local.sizeBytes == attachment.sizeBytes &&
                    runBackupCatching {
                        val file = repository.attachmentStore.file(local)
                        file.isFile && file.canRead() && file.length() == local.sizeBytes && sha256(file, budget) == local.sha256
                    }.getOrDefault(false)
                if (usesIncoming && !localVerified) {
                    val source = File(preparedRoot, "files/${attachment.uuid}")
                    require(source.isFile) { "附件临时文件缺失：${attachment.displayName}" }
                    val destination = File(installed, attachment.uuid)
                    budget.requireSpace(attachment.sizeBytes)
                    source.inputStream().use { input ->
                        FileOutputStream(destination).use { output ->
                            budget.copy(input, budget.limitOutput(output), attachment.sizeBytes)
                            output.fd.sync()
                        }
                    }
                    require(destination.length() == attachment.sizeBytes && sha256(destination, budget) == attachment.sha256) {
                        "安装附件校验失败：${attachment.displayName}"
                    }
                    importedPaths[attachment.uuid] = "$relativeRoot/${attachment.uuid}"
                } else {
                    require(localVerified) { "本机附件已损坏或缺失，请选择备份中的导入版本：${attachment.displayName}" }
                }
            }
            val entities = PortableSnapshotMapper.toEntities(merged, current) { attachment, local ->
                importedPaths[attachment.uuid] ?: requireNotNull(local?.relativePath) { "附件没有可用的本地文件：${attachment.displayName}" }
            }
            // Cancellation after a successful commit must never remove files
            // that the new database already references.
            budget.checkpoint()
            withContext(NonCancellable) {
                replaceDatabase(entities)
                databaseCommitted = true
            }
            val livePaths = entities.attachments.mapTo(hashSetOf()) { it.relativePath }
            current.attachments.filter { it.relativePath !in livePaths }.forEach { old ->
                runCatching { repository.attachmentStore.file(old).delete() }
            }
            if (importedPaths.isEmpty()) installed.deleteRecursively()
        } catch (error: Throwable) {
            if (!databaseCommitted) installed.deleteRecursively()
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
        val hasMedicationHistory = value.schemaVersion >= PREVIOUS_BACKUP_SCHEMA_VERSION
        val familyValue = when (value.schemaVersion) {
            PREVIOUS_BACKUP_SCHEMA_VERSION -> value
            FAMILY_BACKUP_SCHEMA_VERSION ->
                value.copy(members = value.members.map { it.copy(nickname = it.relationship) })
            else -> {
                require(targetMember != null || createDefaultMember) { "请选择旧版备份要导入到的家庭成员" }
                val member = targetMember?.let {
                    PortableFamilyMember(
                        it.uuid, it.name, it.relationship, it.relationship, it.archived,
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
                value.copy(
                    members = listOf(member),
                    conditions = value.conditions.map { it.copy(memberUuid = member.uuid) },
                    records = value.records.map { it.copy(memberUuid = member.uuid) },
                    followUps = value.followUps.map { it.copy(memberUuid = member.uuid) },
                    medications = value.medications.map { it.copy(memberUuid = member.uuid) }
                )
            }
        }
        val medicationByUuid = familyValue.medications.associateBy { it.uuid }
        val nextOrder = hashMapOf<String, Long>()
        return familyValue.copy(
            schemaVersion = BACKUP_SCHEMA_VERSION,
            records = if (hasMedicationHistory) familyValue.records else familyValue.records.map { record ->
                val key = "${record.memberUuid}:${record.recordDate}"
                val order = nextOrder.getOrDefault(key, 0L)
                nextOrder[key] = order + 1L
                record.copy(dayOrder = order)
            },
            medicationSchedules = if (hasMedicationHistory) {
                familyValue.medicationSchedules
            } else familyValue.medicationSchedules.map { schedule ->
                val medication = requireNotNull(medicationByUuid[schedule.medicationUuid]) {
                    "用药计划引用了不存在的药物"
                }
                schedule.copy(
                    effectiveFrom = medication.startDate,
                    effectiveTo = medication.endDate,
                    doseAmountSnapshot = medication.doseAmount,
                    doseUnitSnapshot = medication.doseUnit
                )
            }
        ).also(com.healthtimeline.shared.PortableSnapshotValidator::validate)
    }

    private fun writeDestination(destination: Uri, encrypted: File, budget: BackupResourceBudget) {
        try {
            val descriptor = context.contentResolver.openFileDescriptor(destination, "rwt") ?: error("无法创建备份文件")
            descriptor.use {
                val targetBudget = BackupDestinationStorage.budget(it.fileDescriptor,
                    availableDestinationStorageBytes, availableStorageBytes, budget::checkpoint)
                FileOutputStream(it.fileDescriptor).use { output ->
                    encrypted.inputStream().use { input ->
                        targetBudget.copy(input, targetBudget.limitOutput(output), encrypted.length())
                    }
                    output.fd.sync()
                }
            }
        } catch (error: Throwable) {
            runCatching { context.contentResolver.openOutputStream(destination, "wt")?.close() }
            throw error
        }
    }

    private fun sha256(file: File, budget: BackupResourceBudget): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                budget.checkpoint()
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
