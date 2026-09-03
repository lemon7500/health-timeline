package com.healthtimeline.app.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.healthtimeline.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupService(
    private val context: Context,
    private val repository: HealthRepository
) {
    private val database = repository.database

    suspend fun export(destination: Uri, password: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            repository.mutationMutex.withLock {
                runCatching {
                    require(password.size >= 8) { "备份密码至少需要 8 位" }
                    val tempZip = File.createTempFile("health-backup-", ".zip", context.cacheDir)
                    val encrypted = File.createTempFile("health-backup-", ".encrypted", context.cacheDir)
                    try {
                        val snapshot = loadSnapshot()
                        writeZip(tempZip, snapshot)
                        FileInputStream(tempZip).use { input ->
                            FileOutputStream(encrypted).use { output ->
                                BackupCrypto.encrypt(input, output, password)
                                output.fd.sync()
                            }
                        }
                        writeDestination(destination, encrypted)
                    } finally {
                        tempZip.delete()
                        encrypted.delete()
                    }
                }
            }
        } finally { password.fill('\u0000') }
    }

    suspend fun restore(source: Uri, password: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            repository.mutationMutex.withLock {
                runCatching {
                    val decryptedZip = File.createTempFile("health-restore-", ".zip", context.cacheDir)
                    val stagingRoot = File(context.cacheDir, "restore-${UUID.randomUUID()}")
                    try {
                        try {
                            context.contentResolver.openInputStream(source)?.use { input ->
                                FileOutputStream(decryptedZip).use { output ->
                                    BackupCrypto.decrypt(input, output, password)
                                    output.fd.sync()
                                }
                            } ?: error("无法读取备份文件")
                        } catch (error: IllegalArgumentException) {
                            throw error
                        } catch (error: Throwable) {
                            throw IllegalArgumentException("密码错误或备份文件已损坏", error)
                        }
                        val snapshot = readAndValidateZip(decryptedZip, stagingRoot)
                        applySnapshot(snapshot, stagingRoot)
                    } finally {
                        decryptedZip.delete()
                        stagingRoot.deleteRecursively()
                    }
                }
            }
        } finally { password.fill('\u0000') }
    }

    private suspend fun loadSnapshot() = database.withTransaction {
        BackupSnapshot(
            conditions = database.conditionDao().all(),
            records = database.clinicalRecordDao().all(),
            attachments = database.attachmentDao().all(),
            followUps = database.followUpDao().allSchedules(),
            occurrences = database.followUpDao().allOccurrences(),
            medications = database.medicationDao().allMedications(),
            medicationSchedules = database.medicationDao().allSchedules(),
            medicationLogs = database.medicationDao().allLogs()
        )
    }

    private fun writeDestination(destination: Uri, encrypted: File) {
        try {
            val descriptor = context.contentResolver.openFileDescriptor(destination, "rwt")
                ?: error("无法创建备份文件")
            descriptor.use {
                FileOutputStream(it.fileDescriptor).use { output ->
                    encrypted.inputStream().use { input -> input.copyTo(output) }
                    output.fd.sync()
                }
            }
        } catch (error: Throwable) {
            runCatching { context.contentResolver.openOutputStream(destination, "wt")?.close() }
            throw error
        }
    }

    private fun writeZip(target: File, snapshot: BackupSnapshot) {
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            val portableSnapshot = snapshot.copy(
                attachments = snapshot.attachments.map { attachment ->
                    attachment.copy(relativePath = "attachments/${attachment.recordId}/${File(attachment.relativePath).name}")
                }
            )
            BackupSnapshotValidator.validate(portableSnapshot)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(SnapshotJson.encode(portableSnapshot).toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            snapshot.attachments.forEach { attachment ->
                val file = File(context.filesDir, attachment.relativePath)
                require(file.isFile) { "附件缺失：${attachment.displayName}" }
                require(sha256(file) == attachment.sha256) { "附件校验失败：${attachment.displayName}" }
                zip.putNextEntry(ZipEntry("files/${attachment.id}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun readAndValidateZip(zipFile: File, stagingRoot: File): BackupSnapshot {
        ZipFile(zipFile).use { zip ->
            val manifestEntry = zip.getEntry("manifest.json") ?: error("备份缺少清单")
            require(manifestEntry.size in 1..10_000_000) { "备份清单大小异常" }
            val json = zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
            val snapshot = SnapshotJson.decode(JSONObject(json))
            BackupSnapshotValidator.validate(snapshot)
            val totalSize = snapshot.attachments.fold(0L) { total, item -> Math.addExact(total, item.sizeBytes) }
            require(context.filesDir.usableSpace > totalSize + 20L * 1024L * 1024L) { "手机存储空间不足" }
            val stagedAttachments = File(stagingRoot, "attachments").apply { mkdirs() }
            snapshot.attachments.forEach { attachment ->
                val entry = zip.getEntry("files/${attachment.id}") ?: error("附件缺失：${attachment.displayName}")
                require(entry.size in 0..AttachmentStore.MAX_FILE_BYTES) { "附件大小异常：${attachment.displayName}" }
                val destination = File(stagedAttachments, attachment.relativePath.removePrefix("attachments/"))
                val rootPath = stagedAttachments.canonicalPath + File.separator
                require(destination.canonicalPath.startsWith(rootPath)) { "备份包含不安全路径" }
                require(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建恢复目录" }
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied = Math.addExact(copied, read.toLong())
                            require(copied <= attachment.sizeBytes && copied <= AttachmentStore.MAX_FILE_BYTES) {
                                "附件大小校验失败：${attachment.displayName}"
                            }
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
                require(destination.length() == attachment.sizeBytes) { "附件大小校验失败：${attachment.displayName}" }
                require(sha256(destination) == attachment.sha256) { "附件校验失败：${attachment.displayName}" }
            }
            return snapshot
        }
    }

    private suspend fun applySnapshot(snapshot: BackupSnapshot, stagingRoot: File) {
        val staged = File(stagingRoot, "attachments")
        val restoreId = UUID.randomUUID().toString()
        val restoredParent = File(context.filesDir, "restored_attachments")
        require(restoredParent.isDirectory || restoredParent.mkdirs()) { "无法创建恢复目录" }
        val installed = File(restoredParent, restoreId)
        require(staged.renameTo(installed)) { "无法安装已验证的附件" }
        val relativePrefix = "restored_attachments/$restoreId"
        val restoredSnapshot = snapshot.copy(
            attachments = snapshot.attachments.map { attachment ->
                attachment.copy(
                    relativePath = "$relativePrefix/${attachment.relativePath.removePrefix("attachments/")}"
                )
            }
        )
        var previousAttachments = emptyList<AttachmentEntity>()
        try {
            database.withTransaction {
                previousAttachments = database.attachmentDao().all()
                database.medicationDao().clearLogs()
                database.medicationDao().clearSchedules()
                database.medicationDao().clearMedications()
                database.followUpDao().clearOccurrences()
                database.followUpDao().clearSchedules()
                database.attachmentDao().clear()
                database.clinicalRecordDao().clear()
                database.conditionDao().clear()
                database.conditionDao().insertAll(restoredSnapshot.conditions)
                database.clinicalRecordDao().insertAll(restoredSnapshot.records)
                database.attachmentDao().insertAll(restoredSnapshot.attachments)
                database.followUpDao().insertSchedules(restoredSnapshot.followUps)
                database.followUpDao().insertOccurrences(restoredSnapshot.occurrences)
                database.medicationDao().insertMedications(restoredSnapshot.medications)
                database.medicationDao().insertSchedules(restoredSnapshot.medicationSchedules)
                database.medicationDao().insertLogs(restoredSnapshot.medicationLogs)
            }
        } catch (error: Throwable) {
            installed.deleteRecursively()
            throw error
        }
        // Database commit now points exclusively at the new, already-synced files.
        // Old files are only cleanup; interruption here cannot lose live data.
        val livePaths = restoredSnapshot.attachments.mapTo(hashSetOf()) { it.relativePath }
        previousAttachments.filter { it.relativePath !in livePaths }.forEach { attachment ->
            runCatching { File(context.filesDir, attachment.relativePath).delete() }
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
}

internal data class BackupSnapshot(
    val conditions: List<ConditionEntity>,
    val records: List<ClinicalRecordEntity>,
    val attachments: List<AttachmentEntity>,
    val followUps: List<FollowUpScheduleEntity>,
    val occurrences: List<FollowUpOccurrenceEntity>,
    val medications: List<MedicationEntity>,
    val medicationSchedules: List<MedicationScheduleEntity>,
    val medicationLogs: List<MedicationLogEntity>
)

internal object BackupSnapshotValidator {
    private const val MAX_ROWS = 100_000
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    fun validate(value: BackupSnapshot) {
        val collections = listOf(
            value.conditions, value.records, value.attachments, value.followUps,
            value.occurrences, value.medications, value.medicationSchedules, value.medicationLogs
        )
        require(collections.all { it.size <= MAX_ROWS }) { "备份记录数量异常" }

        uniquePositive(value.conditions.map { it.id }, "病情分类")
        uniquePositive(value.records.map { it.id }, "病历")
        uniquePositive(value.attachments.map { it.id }, "附件")
        uniquePositive(value.followUps.map { it.id }, "复查计划")
        uniquePositive(value.occurrences.map { it.id }, "复查记录")
        uniquePositive(value.medications.map { it.id }, "药物")
        uniquePositive(value.medicationSchedules.map { it.id }, "用药计划")
        uniquePositive(value.medicationLogs.map { it.id }, "用药记录")

        val conditionIds = value.conditions.mapTo(hashSetOf()) { it.id }
        val recordIds = value.records.mapTo(hashSetOf()) { it.id }
        val followUpIds = value.followUps.mapTo(hashSetOf()) { it.id }
        val medicationIds = value.medications.mapTo(hashSetOf()) { it.id }

        value.conditions.forEach {
            require(it.name.isNotBlank() && it.name.length <= 50 && it.notes.length <= 5_000) { "病情分类内容异常" }
            instant(it.createdAt)
        }
        value.records.forEach {
            require(it.conditionId == null || it.conditionId in conditionIds) { "病历引用了不存在的病情分类" }
            LocalDate.parse(it.recordDate)
            require(it.title.isNotBlank() && it.title.length <= 100) { "病历标题异常" }
            require(runCatching { VisitStage.valueOf(it.stage) }.isSuccess) { "病历阶段异常" }
            require(listOf(it.symptoms, it.diagnosis, it.treatment, it.medicationNotes, it.notes).all { text -> text.length <= 20_000 }) { "病历内容过长" }
            require(it.hospital.length <= 200 && it.clinician.length <= 100) { "医院或医生信息过长" }
            instant(it.createdAt); instant(it.updatedAt)
        }
        val attachmentPaths = hashSetOf<String>()
        value.attachments.forEach {
            require(it.recordId in recordIds) { "附件引用了不存在的病历" }
            require(runCatching { AttachmentKind.valueOf(it.kind) }.isSuccess) { "附件类型异常" }
            require(it.displayName.isNotBlank() && it.displayName.length <= 200) { "附件名称异常" }
            require(it.relativePath.startsWith("attachments/") && attachmentPaths.add(it.relativePath)) { "附件路径异常或重复" }
            require(it.sizeBytes in 0..AttachmentStore.MAX_FILE_BYTES) { "附件大小异常" }
            require(sha256Pattern.matches(it.sha256)) { "附件校验值异常" }
            instant(it.createdAt)
        }
        value.followUps.forEach {
            require(it.conditionId == null || it.conditionId in conditionIds) { "复查计划引用了不存在的病情分类" }
            require(it.title.isNotBlank() && it.title.length <= 100) { "复查计划标题异常" }
            require(runCatching { RecurrenceType.valueOf(it.recurrenceType) }.isSuccess) { "复查重复规则异常" }
            require(it.interval in 1..10_000 && it.anchorDayOfMonth in 1..31) { "复查间隔异常" }
            require(it.weekday == null || it.weekday in 1..7) { "复查星期异常" }
            require(it.leadDays in 0..365) { "复查提前天数异常" }
            LocalDate.parse(it.anchorDate); LocalDate.parse(it.nextDueDate); LocalTime.parse(it.reminderTime)
            instant(it.createdAt); instant(it.updatedAt)
        }
        val occurrenceKeys = hashSetOf<String>()
        value.occurrences.forEach {
            require(it.scheduleId in followUpIds) { "复查记录引用了不存在的计划" }
            require(occurrenceKeys.add("${it.scheduleId}:${it.dueDate}")) { "复查记录重复" }
            LocalDate.parse(it.dueDate)
            require(runCatching { OccurrenceStatus.valueOf(it.status) }.isSuccess) { "复查状态异常" }
            it.completedAt?.let(::instant); instant(it.createdAt)
        }
        value.medications.forEach {
            require(it.conditionId == null || it.conditionId in conditionIds) { "药物引用了不存在的病情分类" }
            require(it.name.isNotBlank() && it.name.length <= 100) { "药物名称异常" }
            require(it.doseAmount.isNotBlank() && it.doseAmount.length <= 30 && it.doseUnit.isNotBlank() && it.doseUnit.length <= 20) { "药物剂量异常" }
            require(it.instructions.length <= 5_000) { "服用说明过长" }
            val start = LocalDate.parse(it.startDate)
            it.endDate?.let { end -> require(!LocalDate.parse(end).isBefore(start)) { "药物结束日期早于开始日期" } }
            require(runCatching { MedicationMode.valueOf(it.mode) }.isSuccess) { "用药模式异常" }
            instant(it.createdAt); instant(it.updatedAt)
        }
        value.medicationSchedules.forEach {
            require(it.medicationId in medicationIds) { "用药计划引用了不存在的药物" }
            LocalTime.parse(it.localTime)
        }
        value.medicationLogs.forEach {
            require(it.medicationId in medicationIds) { "用药记录引用了不存在的药物" }
            LocalDateTime.parse(it.scheduledAt)
            it.actualAt?.let(LocalDateTime::parse)
            require(runCatching { MedicationLogStatus.valueOf(it.status) }.isSuccess) { "用药记录状态异常" }
            require(it.doseAmountSnapshot.length <= 30 && it.doseUnitSnapshot.length <= 20) { "历史剂量异常" }
            instant(it.createdAt)
        }
    }

    private fun uniquePositive(ids: List<Long>, label: String) {
        require(ids.all { it > 0 } && ids.toSet().size == ids.size) { "$label ID 异常或重复" }
    }

    private fun instant(value: String) { Instant.parse(value) }
}

private object SnapshotJson {
    fun encode(value: BackupSnapshot): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("createdAt", Instant.now().toString())
        put("conditions", array(value.conditions) { condition(it) })
        put("records", array(value.records) { record(it) })
        put("attachments", array(value.attachments) { attachment(it) })
        put("followUps", array(value.followUps) { followUp(it) })
        put("occurrences", array(value.occurrences) { occurrence(it) })
        put("medications", array(value.medications) { medication(it) })
        put("medicationSchedules", array(value.medicationSchedules) { medicationSchedule(it) })
        put("medicationLogs", array(value.medicationLogs) { medicationLog(it) })
    }

    fun decode(json: JSONObject): BackupSnapshot {
        require(json.optInt("schemaVersion", -1) == 1) { "不支持的备份数据版本" }
        return BackupSnapshot(
            conditions = json.objects("conditions") { condition(it) },
            records = json.objects("records") { record(it) },
            attachments = json.objects("attachments") { attachment(it) },
            followUps = json.objects("followUps") { followUp(it) },
            occurrences = json.objects("occurrences") { occurrence(it) },
            medications = json.objects("medications") { medication(it) },
            medicationSchedules = json.objects("medicationSchedules") { medicationSchedule(it) },
            medicationLogs = json.objects("medicationLogs") { medicationLog(it) }
        )
    }

    private fun condition(v: ConditionEntity) = JSONObject().apply {
        put("id", v.id); put("name", v.name); put("color", v.color); put("notes", v.notes)
        put("archived", v.archived); put("createdAt", v.createdAt)
    }
    private fun condition(v: JSONObject) = ConditionEntity(
        v.long("id"), v.text("name"), v.long("color"), v.text("notes"), v.bool("archived"), v.text("createdAt")
    )

    private fun record(v: ClinicalRecordEntity) = JSONObject().apply {
        put("id", v.id); nullable("conditionId", v.conditionId); put("recordDate", v.recordDate); put("title", v.title)
        put("stage", v.stage); put("symptoms", v.symptoms); put("diagnosis", v.diagnosis); put("treatment", v.treatment)
        put("medicationNotes", v.medicationNotes); put("hospital", v.hospital); put("clinician", v.clinician)
        put("notes", v.notes); put("createdAt", v.createdAt); put("updatedAt", v.updatedAt)
    }
    private fun record(v: JSONObject) = ClinicalRecordEntity(
        v.long("id"), v.nullableLong("conditionId"), v.text("recordDate"), v.text("title"), v.text("stage"),
        v.text("symptoms"), v.text("diagnosis"), v.text("treatment"), v.text("medicationNotes"),
        v.text("hospital"), v.text("clinician"), v.text("notes"), v.text("createdAt"), v.text("updatedAt")
    )

    private fun attachment(v: AttachmentEntity) = JSONObject().apply {
        put("id", v.id); put("recordId", v.recordId); put("kind", v.kind); put("displayName", v.displayName)
        put("mimeType", v.mimeType); put("relativePath", v.relativePath); put("sizeBytes", v.sizeBytes)
        put("sha256", v.sha256); put("createdAt", v.createdAt)
    }
    private fun attachment(v: JSONObject) = AttachmentEntity(
        v.long("id"), v.long("recordId"), v.text("kind"), v.text("displayName"), v.text("mimeType"),
        v.text("relativePath"), v.long("sizeBytes"), v.text("sha256"), v.text("createdAt")
    )

    private fun followUp(v: FollowUpScheduleEntity) = JSONObject().apply {
        put("id", v.id); nullable("conditionId", v.conditionId); put("title", v.title); put("recurrenceType", v.recurrenceType)
        put("interval", v.interval); put("anchorDate", v.anchorDate); put("anchorDayOfMonth", v.anchorDayOfMonth)
        nullable("weekday", v.weekday); put("reminderTime", v.reminderTime); put("leadDays", v.leadDays)
        put("nextDueDate", v.nextDueDate); put("enabled", v.enabled); put("createdAt", v.createdAt); put("updatedAt", v.updatedAt)
    }
    private fun followUp(v: JSONObject) = FollowUpScheduleEntity(
        v.long("id"), v.nullableLong("conditionId"), v.text("title"), v.text("recurrenceType"), v.int("interval"),
        v.text("anchorDate"), v.int("anchorDayOfMonth"), v.nullableInt("weekday"), v.text("reminderTime"),
        v.int("leadDays"), v.text("nextDueDate"), v.bool("enabled"), v.text("createdAt"), v.text("updatedAt")
    )

    private fun occurrence(v: FollowUpOccurrenceEntity) = JSONObject().apply {
        put("id", v.id); put("scheduleId", v.scheduleId); put("dueDate", v.dueDate); put("status", v.status)
        nullable("completedAt", v.completedAt); put("createdAt", v.createdAt)
    }
    private fun occurrence(v: JSONObject) = FollowUpOccurrenceEntity(
        v.long("id"), v.long("scheduleId"), v.text("dueDate"), v.text("status"), v.nullableText("completedAt"), v.text("createdAt")
    )

    private fun medication(v: MedicationEntity) = JSONObject().apply {
        put("id", v.id); nullable("conditionId", v.conditionId); put("name", v.name); put("doseAmount", v.doseAmount)
        put("doseUnit", v.doseUnit); put("instructions", v.instructions); put("startDate", v.startDate)
        nullable("endDate", v.endDate); put("mode", v.mode); put("archived", v.archived)
        put("createdAt", v.createdAt); put("updatedAt", v.updatedAt)
    }
    private fun medication(v: JSONObject) = MedicationEntity(
        v.long("id"), v.nullableLong("conditionId"), v.text("name"), v.text("doseAmount"), v.text("doseUnit"),
        v.text("instructions"), v.text("startDate"), v.nullableText("endDate"), v.text("mode"), v.bool("archived"),
        v.text("createdAt"), v.text("updatedAt")
    )

    private fun medicationSchedule(v: MedicationScheduleEntity) = JSONObject().apply {
        put("id", v.id); put("medicationId", v.medicationId); put("localTime", v.localTime); put("enabled", v.enabled)
    }
    private fun medicationSchedule(v: JSONObject) = MedicationScheduleEntity(
        v.long("id"), v.long("medicationId"), v.text("localTime"), v.bool("enabled")
    )

    private fun medicationLog(v: MedicationLogEntity) = JSONObject().apply {
        put("id", v.id); put("medicationId", v.medicationId); nullable("scheduleId", v.scheduleId)
        put("scheduledAt", v.scheduledAt); nullable("actualAt", v.actualAt); put("status", v.status)
        put("doseAmountSnapshot", v.doseAmountSnapshot); put("doseUnitSnapshot", v.doseUnitSnapshot); put("createdAt", v.createdAt)
    }
    private fun medicationLog(v: JSONObject) = MedicationLogEntity(
        v.long("id"), v.long("medicationId"), v.nullableLong("scheduleId"), v.text("scheduledAt"),
        v.nullableText("actualAt"), v.text("status"), v.text("doseAmountSnapshot"), v.text("doseUnitSnapshot"), v.text("createdAt")
    )

    private fun <T> array(values: List<T>, mapper: (T) -> JSONObject) = JSONArray().also { out -> values.forEach { out.put(mapper(it)) } }
    private fun JSONObject.nullable(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }
    private fun JSONObject.text(key: String) = getString(key)
    private fun JSONObject.long(key: String) = getLong(key)
    private fun JSONObject.int(key: String) = getInt(key)
    private fun JSONObject.bool(key: String) = getBoolean(key)
    private fun JSONObject.nullableText(key: String) = if (isNull(key)) null else getString(key)
    private fun JSONObject.nullableLong(key: String) = if (isNull(key)) null else getLong(key)
    private fun JSONObject.nullableInt(key: String) = if (isNull(key)) null else getInt(key)
    private fun <T> JSONObject.objects(key: String, mapper: (JSONObject) -> T): List<T> {
        val array = getJSONArray(key)
        return List(array.length()) { mapper(array.getJSONObject(it)) }
    }
}
