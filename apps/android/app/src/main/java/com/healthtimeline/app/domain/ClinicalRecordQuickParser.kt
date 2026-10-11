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
    const val SOURCE_PREFIX = "原文记录："

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

    private data class FieldMarker(val field: Field, val start: Int, val valueStart: Int)
    private data class RecordBoundary(val start: Int, val previousEnd: Int)

    // Match segment starts without rewriting their separators. In particular, source notes may
    // contain semicolons, field labels and dates which are data, not new form fields.
    private fun fieldMarkers(input: String): List<FieldMarker> =
        Regex("(?m)(?:^|(?<=[；;]))[\\t 　]*([^\\n；;：:]+)[：:]")
            .findAll(input)
            .mapNotNull { match ->
                aliases[normalizeLabel(match.groupValues[1])]?.let { field ->
                    FieldMarker(field, match.range.first, match.range.last + 1)
                }
            }
            .toList()

    private fun normalizeNewlines(input: String): String = input.replace("\r\n", "\n").replace('\r', '\n')

    private val structuredFields = setOf(Field.DATE, Field.TITLE, Field.CONDITION, Field.STAGE)
    private val standardTemplateFields = Field.entries.toList()
    private val narrativeStart = Regex(
        "(?m)(?:^|(?<=[；;]))[\\t 　]*(?=(?:(?:\\d{4}年)?\\d{1,2}月\\d{1,2}[日号]|\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})(?!\\d))"
    )

    fun parse(input: String, referenceDate: LocalDate = LocalDate.now()): ParsedClinicalRecordDraft {
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
        val normalizedInput = normalizeNewlines(input)
        val markers = fieldMarkers(normalizedInput)
        val terminalNotes = markers.firstOrNull { marker ->
            marker.field == Field.NOTES && markers.any { it.start < marker.start && it.field in structuredFields }
        }
        val parsableInput = terminalNotes?.let { normalizedInput.substring(0, it.start) } ?: normalizedInput
        parsableInput
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
        terminalNotes?.let { marker ->
            val rawNotes = normalizedInput.substring(marker.valueStart)
            values.getOrPut(Field.NOTES) { mutableListOf() } += rawNotes
        }

        if (values.isEmpty()) {
            return parseNaturalNarrative(input, referenceDate, issues)
        }

        val dateText = scalar(Field.DATE, values, issues)
        val condition = scalar(Field.CONDITION, values, issues)
        val title = titleWithCondition(scalar(Field.TITLE, values, issues).orEmpty(), condition)
        val stageText = scalar(Field.STAGE, values, issues)
        val hospital = scalar(Field.HOSPITAL, values, issues).orEmpty()
        val clinician = scalar(Field.CLINICIAN, values, issues).orEmpty()

        if (dateText.isNullOrBlank()) issues.error(Field.DATE.label, "MISSING_DATE", "请在快速录入文字中填写日期")
        if (title.isNullOrBlank()) issues.error(Field.TITLE.label, "MISSING_TITLE", "请在快速录入文字中填写标题")

        val date = dateText?.takeIf { it.isNotBlank() }?.let { parseDate(it, referenceDate) }
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

    /** Split record boundaries on the original text, never on rewritten medical source content. */
    fun parseMany(input: String, referenceDate: LocalDate = LocalDate.now()): ParsedClinicalRecordBatch {
        val formatIssues = buildList {
            if (input.contains("```")) {
                add(
                    QuickEntryIssue(
                        IssueSeverity.ERROR,
                        null,
                        "MARKDOWN_WRAPPER",
                        "检测到 Markdown 代码框，请删除开头和结尾的 ``` 后重新解析"
                    )
                )
            }
        }
        if (input.length > MAX_INPUT_LENGTH || input.isBlank()) {
            return ParsedClinicalRecordBatch(listOf(parse(input, referenceDate)), formatIssues)
        }
        val normalized = normalizeNewlines(input)
        val boundaryIssues = mutableListOf<QuickEntryIssue>()
        val blocks = splitExplicitRecords(normalized, boundaryIssues).flatMap { (offset, section) ->
            splitLegacyRecords(section, offset, normalized, boundaryIssues)
        }
        if (blocks.size > MAX_BATCH_RECORDS) {
            return ParsedClinicalRecordBatch(
                records = emptyList(),
                issues = formatIssues + boundaryIssues + listOf(
                    QuickEntryIssue(
                        IssueSeverity.ERROR,
                        null,
                        "TOO_MANY_RECORDS",
                        "一次最多解析 $MAX_BATCH_RECORDS 条病历，请分批录入"
                    )
                )
            )
        }
        return ParsedClinicalRecordBatch(blocks.map { parse(it, referenceDate) }, formatIssues + boundaryIssues)
    }

    private fun splitExplicitRecords(
        input: String,
        issues: MutableList<QuickEntryIssue>
    ): List<Pair<Int, String>> {
        val boundaries = mutableListOf<RecordBoundary>()
        Regex("(?m)^[\\t 　]*---[\\t 　]*$").findAll(input).forEach { separator ->
            val afterLine = (separator.range.last + 1).let { if (input.getOrNull(it) == '\n') it + 1 else it }
            val blankLines = Regex("^(?:[\\t 　]*\\n)*").find(input.substring(afterLine))!!.value.length
            val headerStart = afterLine + blankLines
            val following = input.substring(headerStart)
            val markers = fieldMarkers(following)
            val hasRecordHeader = markers.firstOrNull()?.let { first ->
                first.start == 0 && first.field == Field.DATE && markers.getOrNull(1)?.field == Field.TITLE
            } == true
            val hasNarrativeHeader = narrativeStart.find(following)?.range?.first == 0
            if (hasRecordHeader || hasNarrativeHeader) {
                // Only the newline introducing the delimiter belongs to the framing.
                // Earlier blank lines can be part of the original source and must survive.
                val previousEnd = if (input.getOrNull(separator.range.first - 1) == '\n') {
                    separator.range.first - 1
                } else separator.range.first
                // Skip blank lines only to recognize the header; they are still source content.
                boundaries += RecordBoundary(afterLine, previousEnd)
            } else {
                ambiguousBoundary(input, separator.range.first, issues)
            }
        }
        val sections = mutableListOf<Pair<Int, String>>()
        var start = 0
        boundaries.forEach { boundary ->
            input.substring(start, boundary.previousEnd).takeIf(String::isNotBlank)?.let { sections += start to it }
            start = boundary.start
        }
        input.substring(start).takeIf(String::isNotBlank)?.let { sections += start to it }
        return sections
    }

    private fun splitLegacyRecords(
        input: String,
        offset: Int,
        wholeInput: String,
        issues: MutableList<QuickEntryIssue>
    ): List<String> {
        val markers = fieldMarkers(input)
        val boundaries = mutableListOf<RecordBoundary>()
        if (markers.any { it.field in structuredFields }) {
            var seenDate = false
            var seenStructuredField = false
            var inNotes = false
            markers.forEachIndexed { index, marker ->
                if (marker.field == Field.DATE) {
                    if (inNotes) {
                        // A repeated, complete standard template separated by a blank line is the
                        // legacy AI format. A date/title alone could be a quoted report: keep it,
                        // and require an explicit separator rather than silently losing source.
                        val layoutBefore = Regex("\\n[\\t 　]*\\n\\z").find(input.substring(0, marker.start))
                        val completeTemplate = standardTemplateFields.indices.all { fieldIndex ->
                            markers.getOrNull(index + fieldIndex)?.field == standardTemplateFields[fieldIndex]
                        }
                        if (layoutBefore != null && completeTemplate) {
                            boundaries += RecordBoundary(marker.start, layoutBefore.range.first)
                            inNotes = false
                            seenDate = true
                        } else if (markers.getOrNull(index + 1)?.field == Field.TITLE) {
                            ambiguousBoundary(wholeInput, offset + marker.start, issues)
                        }
                    } else if (seenDate) {
                        boundaries += RecordBoundary(marker.start, marker.start)
                    } else {
                        seenDate = true
                    }
                }
                if (!inNotes) {
                    if (marker.field == Field.NOTES && seenStructuredField) inNotes = true
                    if (marker.field in structuredFields) seenStructuredField = true
                }
            }
        } else {
            narrativeStart.findAll(input).drop(1).forEach { match ->
                boundaries += RecordBoundary(match.range.first, match.range.first)
            }
        }
        val blocks = mutableListOf<String>()
        var start = 0
        boundaries.forEach { boundary ->
            input.substring(start, boundary.previousEnd).takeIf(String::isNotBlank)?.let(blocks::add)
            start = boundary.start
        }
        input.substring(start).takeIf(String::isNotBlank)?.let(blocks::add)
        return blocks
    }

    private fun ambiguousBoundary(input: String, position: Int, issues: MutableList<QuickEntryIssue>) {
        val line = input.take(position).count { it == '\n' } + 1
        issues.error(
            Field.DATE.label,
            "AMBIGUOUS_RECORD_BOUNDARY",
            "第 $line 行可能是原文内容或另一条记录，已保留原文且未拆分。请核对后在记录之间另起一行写 ---"
        )
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
        val entries = values[field].orEmpty()
            .map { if (field == Field.NOTES) it else it.trim() }
            .filter(String::isNotBlank)
        if (entries.size > 1) {
            issues.warning(field.label, "MERGED_${field.name}", "${field.label}重复出现，已按原顺序合并")
        }
        return entries.joinToString("\n").also { validateLength(field, it, MAX_NARRATIVE_LENGTH, issues) }
    }

    private fun parseDate(value: String, referenceDate: LocalDate): LocalDate? {
        val match = when {
            Regex("^\\d{4}-\\d{1,2}-\\d{1,2}$").matches(value) -> value.split('-')
            Regex("^\\d{4}/\\d{1,2}/\\d{1,2}$").matches(value) -> value.split('/')
            Regex("^\\d{4}年\\d{1,2}月\\d{1,2}日$").matches(value) ->
                Regex("(\\d+)").findAll(value).map { it.value }.toList()
            Regex("^\\d{1,2}月\\d{1,2}[日号]$").matches(value) ->
                listOf(referenceDate.year.toString()) + Regex("(\\d+)").findAll(value).map { it.value }.toList()
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

    private fun parseNaturalNarrative(
        input: String,
        referenceDate: LocalDate,
        issues: MutableList<QuickEntryIssue>
    ): ParsedClinicalRecordDraft {
        val originalText = normalizeNewlines(input)
        val text = originalText.trim()
        val dateMatch = findNarrativeDate(text, referenceDate)
        if (dateMatch == null) {
            issues.error(Field.DATE.label, "MISSING_DATE", "未找到明确日期，请写成“2026年8月29日”或“8月29日”")
        } else if (dateMatch.inferredYear) {
            issues.warning(Field.DATE.label, "INFERRED_YEAR", "未写年份，已按 ${referenceDate.year} 年填写，请核对")
        }

        val diseaseNames = extractDiseaseNames(text)
        val condition = diseaseNames.firstOrNull()
        val clinician = extractClinician(text)
        val hospital = extractHospital(text)
        val examination = extractExamination(text)
        val stage = inferStage(text)
        val titleContext = when {
            text.contains("术后复查") -> "术后复查"
            stage == VisitStage.CHECKUP -> examination?.let { "${it}复查" } ?: "复查"
            stage == VisitStage.SURGERY -> "手术记录"
            stage == VisitStage.AFTER_VISIT -> "就诊记录"
            else -> "病情记录"
        }
        val generatedTitleCore = if (stage == VisitStage.CHECKUP && examination != null) "${examination}复查" else titleContext
        val generatedTitle = titleWithCondition(generatedTitleCore, condition)

        val clauses = narrativeClauses(text)
        val symptoms = clauses.filter { clause ->
            SYMPTOM_WORDS.any(clause::contains)
        }.distinct().joinToString("\n")
        val diagnosisParts = extractDiagnosisStatements(text, diseaseNames).joinToString("\n")
        val treatment = clauses.filter { clause ->
            TREATMENT_WORDS.any(clause::contains)
        }.map(::cleanClause).filter(String::isNotBlank).distinct().joinToString("\n")
        val medication = clauses.filter { clause ->
            MEDICATION_WORDS.any(clause::contains)
        }.map(::cleanClause).filter(String::isNotBlank).distinct().joinToString("\n")

        validateLength(Field.TITLE, generatedTitle.orEmpty(), MAX_TITLE_LENGTH, issues)
        validateLength(Field.CONDITION, condition.orEmpty(), 50, issues)
        validateLength(Field.HOSPITAL, hospital, 100, issues)
        validateLength(Field.CLINICIAN, clinician, 100, issues)
        validateLength(Field.SYMPTOMS, symptoms, MAX_NARRATIVE_LENGTH, issues)
        validateLength(Field.DIAGNOSIS, diagnosisParts, MAX_NARRATIVE_LENGTH, issues)
        validateLength(Field.TREATMENT, treatment, MAX_NARRATIVE_LENGTH, issues)
        validateLength(Field.MEDICATION, medication, MAX_NARRATIVE_LENGTH, issues)
        val sourceNotes = "$SOURCE_PREFIX\n$originalText"
        validateLength(Field.NOTES, sourceNotes, MAX_NARRATIVE_LENGTH, issues)
        issues.warning(null, "NARRATIVE_MODE", "已按自然叙述自动整理，并在备注中保留原文；请核对后再保存")

        return ParsedClinicalRecordDraft(
            recordDate = dateMatch?.date,
            title = generatedTitle,
            conditionName = condition,
            stage = stage,
            symptoms = symptoms,
            diagnosis = diagnosisParts,
            treatment = treatment,
            medicationNotes = medication,
            hospital = hospital,
            clinician = clinician,
            notes = sourceNotes,
            issues = issues,
            unrecognizedSegments = emptyList()
        )
    }

    private data class NarrativeDate(val date: LocalDate, val start: Int, val inferredYear: Boolean)

    private fun findNarrativeDate(text: String, referenceDate: LocalDate): NarrativeDate? {
        val candidates = mutableListOf<NarrativeDate>()
        Regex("(?<!\\d)(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})(?!\\d)").findAll(text).forEach { match ->
            safeDate(match.groupValues[1], match.groupValues[2], match.groupValues[3])
                ?.let { candidates += NarrativeDate(it, match.range.first, false) }
        }
        Regex("(?<!\\d)(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})[日号]").findAll(text).forEach { match ->
            val inferred = match.groupValues[1].isBlank()
            val year = match.groupValues[1].ifBlank { referenceDate.year.toString() }
            safeDate(year, match.groupValues[2], match.groupValues[3])
                ?.let { candidates += NarrativeDate(it, match.range.first, inferred) }
        }
        return candidates.minByOrNull { it.start }
    }

    private fun safeDate(year: String, month: String, day: String): LocalDate? = try {
        LocalDate.of(year.toInt(), month.toInt(), day.toInt())
    } catch (_: DateTimeException) {
        null
    }

    private fun extractClinician(text: String): String {
        val nameChars = "(?:[\\u4e00-\\u9fff·]{1,4}|[A-Za-z][A-Za-z .'-]{0,30})"
        val department = "(?:[\\u4e00-\\u9fff]{1,8}科)?"
        val titled = listOf(
            Regex("(?:去)?(?:见|找|看|咨询|联系)(?:了|过|到)?\\s*$department\\s*($nameChars)(医生|主任|教授|大夫)"),
            Regex("(?:向|问|请教)\\s*$department\\s*($nameChars)(医生|主任|教授|大夫)"),
            Regex("\\d{1,2}[日号]\\s*($nameChars)(医生|主任|教授|大夫)"),
            Regex("(?:^|[，,。；;、\\s])($nameChars)(医生|主任|教授|大夫)(?=[说认建表告意：:，,。；;、\\s]|$)"),
            Regex("(?:主治医生|就诊医生|接诊医生|医生)\\s*[是为叫：:]?\\s*($nameChars)(?=认为|建议|表示|告知|说|诊断|检查|复查|治疗|[，,。；;、\\s]|$)")
        ).firstNotNullOfOrNull { it.find(text) }
        if (titled == null) return ""
        val rawName = titled.groupValues[1]
        val title = titled.groupValues.getOrNull(2).orEmpty().ifBlank { "医生" }
        val cleanedName = cleanClinicianName(rawName)
        return if (cleanedName.isBlank()) "" else "$cleanedName$title"
    }

    private fun cleanClinicianName(value: String): String {
        var result = value.trim()
        DOCTOR_NAME_PREFIXES.forEach { prefix -> result = result.removePrefix(prefix) }
        if ('科' in result && result.substringAfterLast('科').isNotBlank()) result = result.substringAfterLast('科')
        return result.takeLast(6)
    }

    private fun extractHospital(text: String): String =
        Regex("([\\u4e00-\\u9fffA-Za-z0-9·]{2,30}(?:医院|门诊部|诊所|医疗中心))")
            .find(text)?.groupValues?.get(1).orEmpty()

    private fun extractExamination(text: String): String? =
        Regex("([\\u4e00-\\u9fffA-Za-z0-9]{1,12}(?:CT|MRI|MR|B超|彩超|核磁|X线))(?=报告|检查|结果)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)

    private fun extractDiseaseNames(text: String): List<String> {
        val diseaseRegex = Regex(
            "([\\u4e00-\\u9fff]{2,14}?(?:综合征|坏死|骨折|感染|结节|囊肿|息肉|溃疡|增生|肿块|积液|梗阻|炎|癌|瘤|病|症))" +
                "(?=$|伴|并|和|及|或|、|\\s)"
        )
        val withoutDates = text
            .replace(Regex("(?<!\\d)\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}(?!\\d)"), "")
            .replace(Regex("(?<!\\d)(?:\\d{4}年)?\\d{1,2}月\\d{1,2}[日号]"), "")
        return withoutDates.split(Regex("[，,。；;：:\\n]+"))
            .asSequence()
            .map(::cleanClause)
            .flatMap { clause ->
                val marker = DISEASE_CONTEXT_MARKERS
                    .mapNotNull { value -> clause.lastIndexOf(value).takeIf { it >= 0 }?.let { it to value } }
                    .maxByOrNull { it.first }
                val searchable = marker?.let { (index, value) -> clause.substring(index + value.length) } ?: clause
                diseaseRegex.findAll(searchable).map { it.groupValues[1] }
            }
            .map { candidate ->
                DISEASE_LEADING_WORDS.fold(candidate) { value, prefix -> value.removePrefix(prefix) }
            }
            .filter { it.length in 2..16 && it !in DISEASE_FALSE_POSITIVES }
            .distinct()
            .toList()
    }

    private fun extractDiagnosisStatements(text: String, diseaseNames: List<String>): List<String> {
        val statements = mutableListOf<String>()
        diseaseNames.forEach { disease ->
            val index = text.indexOf(disease)
            val prefix = if (index <= 0) "" else text.substring((index - 12).coerceAtLeast(0), index)
            val qualifier = when {
                listOf("疑似", "怀疑").any(prefix::contains) -> "疑似："
                listOf("有可能是", "可能是", "可能为").any(prefix::contains) -> "可能："
                listOf("考虑为", "考虑").any(prefix::contains) -> "考虑："
                else -> ""
            }
            statements += "$qualifier$disease"
        }
        Regex("(?:诊断|诊断结果|确诊)\\s*[为是：:]\\s*([^，,。；;\\n]{2,80})")
            .findAll(text)
            .map { trimDiagnosisTail(cleanClause(it.groupValues[1])) }
            .filter(String::isNotBlank)
            .forEach(statements::add)
        return statements.distinct()
    }

    private fun trimDiagnosisTail(value: String): String {
        val cutAt = DIAGNOSIS_TAIL_MARKERS.map(value::indexOf).filter { it > 0 }.minOrNull()
        return if (cutAt == null) value else value.substring(0, cutAt).trim()
    }

    private fun inferStage(text: String): VisitStage = when {
        Regex("术后.{0,8}(复查|检查|就诊)").containsMatchIn(text) -> VisitStage.CHECKUP
        listOf("复查", "再次去见", "再次就诊", "报告出来", "检查结果").any(text::contains) -> VisitStage.CHECKUP
        listOf("接受手术", "进行了手术", "完成手术").any(text::contains) -> VisitStage.SURGERY
        listOf("就诊", "看医生", "见了", "找了").any(text::contains) -> VisitStage.AFTER_VISIT
        else -> VisitStage.OTHER
    }

    private fun narrativeClauses(text: String): List<String> =
        text.split(Regex("[。！？!?\\n]+"))
            .map(::cleanClause)
            .filter(String::isNotBlank)

    private fun cleanClause(value: String): String =
        value.trim().replace(Regex("^[（(]?\\d+[）).、]\\s*"), "").trim('，', ',', '；', ';', ' ')

    fun titleWithCondition(title: String, condition: String?): String {
        val cleanTitle = title.trim()
        val cleanCondition = condition?.trim().orEmpty()
        if (cleanTitle.isBlank()) return cleanCondition.takeIf(String::isNotBlank)?.let { "${it}病情记录" }.orEmpty()
        if (cleanCondition.isBlank() || cleanTitle.startsWith(cleanCondition, ignoreCase = true)) return cleanTitle
        val remainingTitle = cleanTitle
            .replace(Regex(Regex.escape(cleanCondition), RegexOption.IGNORE_CASE), "")
            .trim()
            .trim('-', '—', '－', '_', '：', ':', '·', ' ', '|', '/', '／')
        return "$cleanCondition$remainingTitle"
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

    private const val MAX_TITLE_LENGTH = 100
    private val SYMPTOM_WORDS = listOf(
        "疼", "痛", "肿", "发热", "发烧", "鼻塞", "咳", "出血", "麻木", "头晕", "恶心", "炎症严重", "不适"
    )
    private val TREATMENT_WORDS = listOf(
        "手术", "治疗", "复查", "再来", "观察", "去除", "取出", "保持", "控制", "清洗", "冲洗", "处理"
    )
    private val MEDICATION_WORDS = listOf(
        "用药", "服药", "吃药", "开了药", "药物", "口服", "注射", "打地舒单抗", "再吃"
    )
    private val DISEASE_LEADING_WORDS = listOf(
        "医生认为", "医生意见", "检查发现", "再次复查", "复查", "目前是", "目前为", "患有", "存在", "发现", "并发", "伴"
    )
    private val DISEASE_FALSE_POSITIVES = setOf("病情", "炎症", "症状")
    private val DISEASE_CONTEXT_MARKERS = listOf(
        "诊断结果为", "诊断结果是", "有可能是", "可能是", "可能为", "确诊为", "诊断为", "诊断是",
        "考虑为", "考虑", "疑似", "怀疑", "再次复查", "复查", "检查发现", "发现", "观察", "治疗",
        "咨询", "医生意见", "医生认为", "医生建议", "建议", "患有", "存在"
    )
    private val DIAGNOSIS_TAIL_MARKERS = listOf("并建议", "同时建议", "建议", "后续治疗", "治疗方案", "需要治疗", "用药")
    private val DOCTOR_NAME_PREFIXES = listOf(
        "今天", "当天", "再次", "又去", "去", "找", "看", "咨询", "联系", "主治", "接诊", "负责"
    )
}
