import SwiftUI
import UIKit

struct QuickEntryView: View {
    @EnvironmentObject private var store: HealthStore
    @Environment(\.dismiss) private var dismiss
    let memberId: UUID?
    @State private var input = ""
    @State private var drafts: [QuickClinicalRecordDraft] = []
    @State private var parseIssues: [String] = []
    @State private var showPrompt = false
    @State private var confirmingArchivedConditions = false

    private var issues: [String] {
        parseIssues + drafts.enumerated().flatMap { StructuredQuickEntryParser.validate($0.element, index: $0.offset + 1) }
    }
    private var archivedConditionNames: [String] {
        guard let memberId else { return [] }
        let requested = Set(drafts.map { $0.conditionName.trimmingCharacters(in: .whitespacesAndNewlines).localizedLowercase }.filter { !$0.isEmpty })
        return store.state.conditions.filter { $0.memberId == memberId && $0.archived && requested.contains($0.name.localizedLowercase) }.map(\.name).sorted()
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("AI 辅助整理（可选）") {
                    Text("复杂或跨多日内容可先复制提示词，自行脱敏后交给任意外部 AI。应用不会联网，也不会把病历原文复制给 AI。")
                        .font(.footnote).foregroundStyle(.secondary)
                    Button("复制 AI 整理提示词") {
                        UIPasteboard.general.string = AiRecordFormattingPromptBuilder.build(defaultYear: HealthDate.calendar.component(.year, from: Date()))
                        store.noticeMessage = "提示词已复制，未复制病历原文"
                    }
                    Button("预览提示词") { showPrompt.toggle() }
                    if showPrompt {
                        Text(AiRecordFormattingPromptBuilder.build(defaultYear: HealthDate.calendar.component(.year, from: Date())))
                            .font(.caption).textSelection(.enabled)
                    }
                }
                Section("粘贴 AI 输出") {
                    TextEditor(text: $input).frame(minHeight: 150)
                    HStack {
                        Button("从剪贴板粘贴") { input = UIPasteboard.general.string ?? "" }
                        Button("解析并核对") { parse() }
                        Button("清空", role: .destructive) { input = ""; drafts = []; parseIssues = [] }
                    }
                }
                if !issues.isEmpty {
                    Section("需要修正") { ForEach(issues, id: \.self) { Text($0).foregroundStyle(.red) } }
                }
                ForEach($drafts) { $draft in
                    Section {
                        DisclosureGroup("\(draft.recordDate.isEmpty ? "日期待填" : draft.recordDate) · \(draft.title.isEmpty ? "标题待填" : draft.title)") {
                            TextField("日期 yyyy-MM-dd", text: $draft.recordDate)
                            TextField("标题（病名在前）", text: $draft.title)
                            TextField("病情分类", text: $draft.conditionName)
                            Picker("记录类型", selection: $draft.stage) {
                                Text("就诊前").tag("BEFORE_VISIT"); Text("就诊后").tag("AFTER_VISIT")
                                Text("复查").tag("CHECKUP"); Text("手术").tag("SURGERY"); Text("其他").tag("OTHER")
                            }
                            TextField("症状/病情", text: $draft.symptoms, axis: .vertical)
                            TextField("诊断", text: $draft.diagnosis, axis: .vertical)
                            TextField("治疗方案", text: $draft.treatment, axis: .vertical)
                            TextField("就诊用药记录", text: $draft.medicationNotes, axis: .vertical)
                            TextField("医院", text: $draft.hospital)
                            TextField("医生", text: $draft.clinician)
                            TextField("其他备注（必须保留原文记录）", text: $draft.notes, axis: .vertical).lineLimit(4...16)
                            Button("删除这条", role: .destructive) { drafts.removeAll { $0.id == draft.id } }
                        }
                    }
                }
                if !drafts.isEmpty {
                    Section {
                        Button("逐条核对后保存 \(drafts.count) 条") {
                            if archivedConditionNames.isEmpty { save() } else { confirmingArchivedConditions = true }
                        }.disabled(!issues.isEmpty)
                        Text("全部记录和新分类使用一次数据库提交；任一条失败都不会留下半批资料。")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("快速录入")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
            .confirmationDialog("恢复并使用已归档的病情分类？", isPresented: $confirmingArchivedConditions, titleVisibility: .visible) {
                Button("恢复并保存") { save() }
                Button("取消", role: .cancel) {}
            } message: { Text(archivedConditionNames.joined(separator: "、")) }
        }
    }

    private func parse() {
        let result = StructuredQuickEntryParser.parse(input)
        drafts = result.drafts; parseIssues = result.issues
    }

    private func save() {
        guard issues.isEmpty else { return }
        if store.addQuickRecords(drafts, memberId: memberId) { dismiss() }
    }
}
