@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.healthtimeline.shared

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.time.Instant

object PortableSnapshotValidator {
    private const val MAX_ROWS = 100_000
    private const val MAX_ATTACHMENT_BYTES = 100L * 1024L * 1024L
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val timePattern = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")

    fun validate(value: PortableSnapshot) {
        require(value.schemaVersion == BACKUP_SCHEMA_VERSION) { "不支持的备份数据版本" }
        instant(value.exportedAt)
        require(value.sourcePlatform in setOf("android", "ios", "harmony", "test")) { "来源平台异常" }
        uuid(value.sourceInstallationId, "来源安装标识")
        val collections = listOf(
            value.conditions, value.records, value.attachments, value.followUps,
            value.occurrences, value.medications, value.medicationSchedules, value.medicationLogs
        )
        require(collections.all { it.size <= MAX_ROWS }) { "备份记录数量异常" }

        unique(value.conditions.map { it.uuid }, "病情分类")
        unique(value.records.map { it.uuid }, "病历")
        unique(value.attachments.map { it.uuid }, "附件")
        unique(value.followUps.map { it.uuid }, "复查计划")
        unique(value.occurrences.map { it.uuid }, "复查记录")
        unique(value.medications.map { it.uuid }, "药物")
        unique(value.medicationSchedules.map { it.uuid }, "用药计划")
        unique(value.medicationLogs.map { it.uuid }, "用药记录")

        val conditionIds = value.conditions.mapTo(hashSetOf()) { it.uuid }
        val recordIds = value.records.mapTo(hashSetOf()) { it.uuid }
        val followUpIds = value.followUps.mapTo(hashSetOf()) { it.uuid }
        val medicationIds = value.medications.mapTo(hashSetOf()) { it.uuid }
        val scheduleIds = value.medicationSchedules.mapTo(hashSetOf()) { it.uuid }

        value.conditions.forEach {
            require(it.name.isNotBlank() && it.name.length <= 50 && it.notes.length <= 5_000) { "病情分类内容异常" }
            timestamps(it.createdAt, it.updatedAt)
        }
        value.records.forEach {
            require(it.conditionUuid == null || it.conditionUuid in conditionIds) { "病历引用了不存在的病情分类" }
            date(it.recordDate)
            require(it.title.isNotBlank() && it.title.length <= 100) { "病历标题异常" }
            require(it.stage in setOf("BEFORE_VISIT", "AFTER_VISIT", "CHECKUP", "SURGERY", "OTHER")) { "病历阶段异常" }
            require(listOf(it.symptoms, it.diagnosis, it.treatment, it.medicationNotes, it.notes).all { text -> text.length <= 20_000 }) { "病历内容过长" }
            require(it.hospital.length <= 200 && it.clinician.length <= 100) { "医院或医生信息过长" }
            timestamps(it.createdAt, it.updatedAt)
        }
        val archivePaths = hashSetOf<String>()
        value.attachments.forEach {
            require(it.recordUuid in recordIds) { "附件引用了不存在的病历" }
            require(it.kind == "IMAGE" || it.kind == "PDF") { "附件类型异常" }
            require(it.displayName.isNotBlank() && it.displayName.length <= 200) { "附件名称异常" }
            require(it.archivePath == "files/${it.uuid}" && archivePaths.add(it.archivePath)) { "附件路径异常或重复" }
            require(it.sizeBytes in 0..MAX_ATTACHMENT_BYTES) { "附件大小异常" }
            require(sha256Pattern.matches(it.sha256)) { "附件校验值异常" }
            timestamps(it.createdAt, it.updatedAt)
        }
        value.followUps.forEach {
            require(it.conditionUuid == null || it.conditionUuid in conditionIds) { "复查计划引用了不存在的病情分类" }
            require(it.title.isNotBlank() && it.title.length <= 100) { "复查计划标题异常" }
            require(it.recurrenceType in setOf("ONCE", "EVERY_N_DAYS", "EVERY_N_WEEKS", "EVERY_N_MONTHS")) { "复查重复规则异常" }
            require(it.interval in 1..10_000 && it.anchorDayOfMonth in 1..31) { "复查间隔异常" }
            require(it.weekday == null || it.weekday in 1..7) { "复查星期异常" }
            require(it.leadDays in 0..365 && timePattern.matches(it.reminderTime)) { "复查提醒时间异常" }
            date(it.anchorDate); date(it.nextDueDate); timestamps(it.createdAt, it.updatedAt)
        }
        value.occurrences.forEach {
            require(it.scheduleUuid in followUpIds) { "复查记录引用了不存在的计划" }
            date(it.dueDate)
            require(it.status in setOf("PENDING", "DONE", "SKIPPED")) { "复查状态异常" }
            it.completedAt?.let(::instant); timestamps(it.createdAt, it.updatedAt)
        }
        value.medications.forEach {
            require(it.conditionUuid == null || it.conditionUuid in conditionIds) { "药物引用了不存在的病情分类" }
            require(it.name.isNotBlank() && it.name.length <= 100) { "药物名称异常" }
            require(it.doseAmount.isNotBlank() && it.doseAmount.length <= 30 && it.doseUnit.isNotBlank() && it.doseUnit.length <= 20) { "药物剂量异常" }
            require(it.instructions.length <= 5_000 && it.mode in setOf("SCHEDULED", "AS_NEEDED")) { "用药内容异常" }
            val start = LocalDate.parse(it.startDate)
            it.endDate?.let { end -> require(LocalDate.parse(end) >= start) { "药物结束日期早于开始日期" } }
            timestamps(it.createdAt, it.updatedAt)
        }
        value.medicationSchedules.forEach {
            require(it.medicationUuid in medicationIds && timePattern.matches(it.localTime)) { "用药计划异常" }
            instant(it.updatedAt)
        }
        value.medicationLogs.forEach {
            require(it.medicationUuid in medicationIds) { "用药记录引用了不存在的药物" }
            require(it.scheduleUuid == null || it.scheduleUuid in scheduleIds) { "用药记录引用了不存在的服药计划" }
            LocalDateTime.parse(it.scheduledAt); it.actualAt?.let(LocalDateTime::parse)
            require(it.status == "TAKEN" || it.status == "SKIPPED") { "用药状态异常" }
            require(it.doseAmountSnapshot.length <= 30 && it.doseUnitSnapshot.length <= 20) { "历史剂量异常" }
            timestamps(it.createdAt, it.updatedAt)
        }
    }

    private fun unique(values: List<String>, label: String) {
        values.forEach { uuid(it, label) }
        require(values.toSet().size == values.size) { "$label UUID 重复" }
    }

    private fun uuid(value: String, label: String) = require(uuidPattern.matches(value)) { "$label UUID 异常" }
    private fun date(value: String) { LocalDate.parse(value) }
    private fun instant(value: String) {
        require(value.endsWith("Z")) { "时间戳必须使用 UTC" }
        Instant.parse(value)
    }
    private fun timestamps(createdAt: String, updatedAt: String) { instant(createdAt); instant(updatedAt) }
}
