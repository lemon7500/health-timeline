package com.healthtimeline.app.domain

import com.healthtimeline.app.data.VisitStage
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

enum class IssueSeverity { ERROR, WARNING }

data class QuickEntryIssue(
    val severity: IssueSeverity,
    val field: String?,
    val code: String,
    val message: String
)

data class ParsedClinicalRecordDraft(
    val recordDate: LocalDate?,
    val title: String?,
    val conditionName: String?,
    val stage: VisitStage?,
    val symptoms: String,
    val diagnosis: String,
    val treatment: String,
    val medicationNotes: String,
    val hospital: String,
    val clinician: String,
    val notes: String,
    val issues: List<QuickEntryIssue>,
    val unrecognizedSegments: List<String>
) {
    val hasErrors: Boolean get() = issues.any { it.severity == IssueSeverity.ERROR }
}

data class ParsedClinicalRecordBatch(
    val records: List<ParsedClinicalRecordDraft>,
    val issues: List<QuickEntryIssue> = emptyList()
) {
    val hasErrors: Boolean
        get() = issues.any { it.severity == IssueSeverity.ERROR } || records.any { it.hasErrors }
}

object ClinicalRecordQuickParser {
    const val MAX_INPUT_LENGTH = 50_000
    const val MAX_NARRATIVE_LENGTH = 10_000
    const val MAX_BATCH_RECORDS = 200
    const val TEMPLATE_DATE_TOKEN = "{{date}}"

    val template: String = """
        日期：$TEMPLATE_DATE_TOKEN
        标题：
        病情分类：
        记录类型：
        症状/病情：
        诊断：
        治疗方案：
        就诊用药记录：
        医院：
        医生：
        其他备注：
    """.trimIndent()

    private enum class Field(val label: String, val narrative: Boolean = false) {
        DATE("日期"),
        TITLE("标题"),
        CONDITION("病情分类"),
        STAGE("记录类型"),
        SYMPTOMS("症状/病情", true),
        DIAGNOSIS("诊断", true),
        TREATMENT("治疗方案", true),
        MEDICATION("就诊用药记录", true),
        HOSPITAL("医院"),
        CLINICIAN("医生"),
        NOTES("其他备注", true)
    }

    private val aliases = buildMap {
        aliases(Field.DATE, "日期", "就诊日期", "记录日期")
        aliases(Field.TITLE, "标题", "小标题")
        aliases(Field.CONDITION, "病情分类", "疾病分类", "分类")
        aliases(Field.STAGE, "记录类型", "就诊类型", "类型")
        aliases(Field.SYMPTOMS, "症状/病情", "症状", "病情", "病情描述")
        aliases(Field.DIAGNOSIS, "诊断", "诊断结果")
        aliases(Field.TREATMENT, "治疗方案", "治疗", "处理", "治疗记录")
        aliases(Field.MEDICATION, "就诊用药记录", "用药记录", "药物记录", "用药", "药物")
        aliases(Field.HOSPITAL, "医院", "就诊医院")
        aliases(Field.CLINICIAN, "医生", "主治医生", "就诊医生")
        aliases(Field.NOTES, "其他备注", "备注", "其他")
    }

    fun parse(input: String): ParsedClinicalRecordDraft {
        val issues = mutableListOf<QuickEntryIssue>()
        if (input.isBlank()) {
            issues.error(null, "EMPTY_INPUT", "请先输入或粘贴病历文字")
            return emptyDraft(issues)
        }
        if (input.length > MAX_INPUT_LENGTH) {
            issues.error(null, "INPUT_TOO_LONG", "快速录入内容不能超过 $MAX_INPUT_LENGTH 个字符")
            return emptyDraft(issues)
        }

        val values = mutableMapOf<Field, MutableList<String>>()
        val unrecognized = mutableListOf<String>()
        var currentField: Field? = null
        input.replace("\r\n", "\n").replace('\r', '\n')
            .split(Regex("[\n；;]+"))
            .forEach { rawSegment ->
                val segment = rawSegment.trim()
                if (segment.isEmpty()) return@forEach
                val delimiter = segment.indexOfAny(charArrayOf('：', ':'))
                if (delimiter >= 0) {
                    val field = aliases[normalizeLabel(segment.substring(0, delimiter))]
                    if (field == null) {
                        unrecognized += segment
                        currentField = null
                    } else {
                        values.getOrPut(field) { mutableListOf() } += segment.substring(delimiter + 1).trim()
                        currentField = field
                    }
                } else if (currentField != null) {
                    val entries = values.getValue(currentField!!)
                    val previous = entries.removeAt(entries.lastIndex)
                    entries += listOf(previous, segment).filter { it.isNotBlank() }.joinToString("\n")
                } else {
                    unrecognized += segment
                }
            }

        val dateText = scalar(Field.DATE, values, issues)
        val title = scalar(Field.TITLE, values, issues)
        val condition = scalar(Field.CONDITION, values, issues)
        val stageText = scalar(Field.STAGE, values, issues)
        val hospital = scalar(Field.HOSPITAL, values, issues).orEmpty()
        val clinician = scalar(Field.CLINICIAN, values, issues).orEmpty()

        if (dateText.isNullOrBlank()) issues.error(Field.DATE.label, "MISSING_DATE", "请在快速录入文字中填写日期")
        if (title.isNullOrBlank()) issues.error(Field.TITLE.label, "MISSING_TITLE", "请在快速录入文字中填写标题")

        val date = dateText?.takeIf { it.isNotBlank() }?.let { parseDate(it) }
        if (!dateText.isNullOrBlank() && date == null) {
            issues.error(Field.DATE.label, "INVALID_DATE", "日期须使用 2026-09-06、2026/9/6 或 2026年9月6日")
        }
        validateLength(Field.TITLE, title.orEmpty(), 100, issues)
        validateLength(Field.CONDITION, condition.orEmpty(), 50, issues)
        validateLength(Field.HOSPITAL, hospital, 100, issues)
        validateLength(Field.CLINICIAN, clinician, 100, issues)

        val stage = parseStage(stageText)
        if (!stageText.isNullOrBlank() && stage == null) {
            issues.warning(Field.STAGE.label, "UNKNOWN_STAGE", "未识别记录类型“$stageText”，请手动选择")
        }

        val symptoms = narrative(Field.SYMPTOMS, values, issues)
        val diagnosis = narrative(Field.DIAGNOSIS, values, issues)
        val treatment = narrative(Field.TREATMENT, values, issues)
        val medication = narrative(Field.MEDICATION, values, issues)
        val notes = narrative(Field.NOTES, values, issues)
        if (unrecognized.isNotEmpty()) {
            issues.warning(null, "UNRECOGNIZED_CONTENT", "有 ${unrecognized.size} 段内容未识别，请核对或追加到其他备注")
        }

        return ParsedClinicalRecordDraft(
            recordDate = date,
            title = title,
            conditionName = condition,
            stage = stage,
            symptoms = symptoms,
            diagnosis = diagnosis,
            treatment = treatment,
            medicationNotes = medication,
            hospital = hospital,
            clinician = clinician,
            notes = notes,
            issues = issues,
            unrecognizedSegments = unrecognized
        )
    }

    /**
     * Splits a long entry whenever a new labelled date starts, then parses every block independently.
     * This keeps the single-record parser strict while allowing several days to be reviewed and saved together.
     */
    fun parseMany(input: String): ParsedClinicalRecordBatch {
        if (input.length > MAX_INPUT_LENGTH || input.isBlank()) {
            return ParsedClinicalRecordBatch(listOf(parse(input)))
        }
        val normalized = input.replace("\r\n", "\n").replace('\r', '\n')
            .replace('；', '\n').replace(';', '\n')
        val dateStarts = Regex("(?m)^\\s*(?:日期|就诊日期|记录日期)\\s*[：:]")
            .findAll(normalized)
            .map { it.range.first }
            .toList()
        if (dateStarts.size <= 1) return ParsedClinicalRecordBatch(listOf(parse(normalized)))

        val blocks = dateStarts.mapIndexed { index, start ->
            val blockStart = if (index == 0) 0 else start
            val blockEnd = dateStarts.getOrNull(index + 1) ?: normalized.length
            normalized.substring(blockStart, blockEnd).trim()
        }.filter { it.isNotBlank() }
        if (blocks.size > MAX_BATCH_RECORDS) {
            return ParsedClinicalRecordBatch(
                records = emptyList(),
                issues = listOf(
                    QuickEntryIssue(
                        IssueSeverity.ERROR,
                        null,
                        "TOO_MANY_RECORDS",
                        "一次最多解析 $MAX_BATCH_RECORDS 条病历，请分批录入"
                    )
                )
            )
        }
        return ParsedClinicalRecordBatch(blocks.map(::parse))
    }

    fun templateFor(date: LocalDate): String = template.replace(TEMPLATE_DATE_TOKEN, date.toString())

    fun normalizeConditionName(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("[\\s　]+"), "")

    private fun scalar(
        field: Field,
        values: Map<Field, List<String>>,
        issues: MutableList<QuickEntryIssue>
    ): String? {
        val entries = values[field].orEmpty().map(String::trim).filter(String::isNotEmpty)
        if (entries.isEmpty()) return null
        val distinct = entries.distinct()
        if (distinct.size > 1) {
            issues.error(field.label, "CONFLICTING_${field.name}", "${field.label}出现多个不同内容，请保留一个")
        } else if (values[field].orEmpty().size > 1) {
            issues.warning(field.label, "DUPLICATE_${field.name}", "${field.label}重复出现，已使用相同内容")
        }
        return distinct.first()
    }

    private fun narrative(
        field: Field,
        values: Map<Field, List<String>>,
        issues: MutableList<QuickEntryIssue>
    ): String {
        val entries = values[field].orEmpty().map(String::trim).filter(String::isNotEmpty)
        if (entries.size > 1) {
            issues.warning(field.label, "MERGED_${field.name}", "${field.label}重复出现，已按原顺序合并")
        }
        return entries.joinToString("\n").also { validateLength(field, it, MAX_NARRATIVE_LENGTH, issues) }
    }

    private fun parseDate(value: String): LocalDate? {
        val match = when {
            Regex("^\\d{4}-\\d{1,2}-\\d{1,2}$").matches(value) -> value.split('-')
            Regex("^\\d{4}/\\d{1,2}/\\d{1,2}$").matches(value) -> value.split('/')
            Regex("^\\d{4}年\\d{1,2}月\\d{1,2}日$").matches(value) ->
                Regex("(\\d+)").findAll(value).map { it.value }.toList()
            else -> return null
        }
        return try {
            LocalDate.of(match[0].toInt(), match[1].toInt(), match[2].toInt())
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun parseStage(value: String?): VisitStage? = when (normalizeLabel(value.orEmpty())) {
        "就诊前", "诊前", "before", "beforevisit" -> VisitStage.BEFORE_VISIT
        "就诊后", "诊后", "after", "aftervisit" -> VisitStage.AFTER_VISIT
        "复查", "检查", "checkup" -> VisitStage.CHECKUP
        "手术", "术后", "surgery" -> VisitStage.SURGERY
        "其他", "其它", "other" -> VisitStage.OTHER
        else -> null
    }

    private fun validateLength(field: Field, value: String, limit: Int, issues: MutableList<QuickEntryIssue>) {
        if (value.length > limit) {
            issues.error(field.label, "${field.name}_TOO_LONG", "${field.label}不能超过 $limit 个字符")
        }
    }

    private fun normalizeLabel(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("[\\s　]+"), "")

    private fun MutableMap<String, Field>.aliases(field: Field, vararg values: String) {
        values.forEach { put(normalizeLabel(it), field) }
    }

    private fun MutableList<QuickEntryIssue>.error(field: String?, code: String, message: String) {
        add(QuickEntryIssue(IssueSeverity.ERROR, field, code, message))
    }

    private fun MutableList<QuickEntryIssue>.warning(field: String?, code: String, message: String) {
        add(QuickEntryIssue(IssueSeverity.WARNING, field, code, message))
    }

    private fun emptyDraft(issues: List<QuickEntryIssue>) = ParsedClinicalRecordDraft(
        recordDate = null,
        title = null,
        conditionName = null,
        stage = null,
        symptoms = "",
        diagnosis = "",
        treatment = "",
        medicationNotes = "",
        hospital = "",
        clinician = "",
        notes = "",
        issues = issues,
        unrecognizedSegments = emptyList()
    )
}
