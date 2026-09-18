import PDFKit
import SwiftUI
import UniformTypeIdentifiers

struct RecordEditor: View {
    @EnvironmentObject private var store: HealthStore
    @Environment(\.dismiss) private var dismiss
    let original: ClinicalRecord?
    private let fixedMemberId: UUID?
    @State private var date: Date
    @State private var title: String
    @State private var conditionName = ""
    @State private var stage: String
    @State private var symptoms: String
    @State private var diagnosis: String
    @State private var treatment: String
    @State private var medicationNotes: String
    @State private var hospital: String
    @State private var clinician: String
    @State private var notes: String

    init(original: ClinicalRecord? = nil, memberId: UUID? = nil) {
        self.original = original; fixedMemberId = original?.memberId ?? memberId
        _date = State(initialValue: original?.recordDate ?? Date()); _title = State(initialValue: original?.title ?? "")
        _stage = State(initialValue: original?.stage ?? "OTHER"); _symptoms = State(initialValue: original?.symptoms ?? "")
        _diagnosis = State(initialValue: original?.diagnosis ?? ""); _treatment = State(initialValue: original?.treatment ?? "")
        _medicationNotes = State(initialValue: original?.medicationNotes ?? ""); _hospital = State(initialValue: original?.hospital ?? "")
        _clinician = State(initialValue: original?.clinician ?? ""); _notes = State(initialValue: original?.notes ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("基本资料") {
                    DatePicker("日期", selection: $date, displayedComponents: .date)
                    TextField("标题（病名在前）", text: $title)
                    if original == nil { TextField("病情分类（可选）", text: $conditionName) }
                    Picker("记录类型", selection: $stage) {
                        Text("就诊前").tag("BEFORE_VISIT"); Text("就诊后").tag("AFTER_VISIT")
                        Text("复查").tag("CHECKUP"); Text("手术").tag("SURGERY"); Text("其他").tag("OTHER")
                    }
                }
                Section("病情与治疗") {
                    TextField("症状/病情", text: $symptoms, axis: .vertical).lineLimit(2...8)
                    TextField("诊断", text: $diagnosis, axis: .vertical).lineLimit(2...8)
                    TextField("治疗方案", text: $treatment, axis: .vertical).lineLimit(2...8)
                    TextField("就诊用药记录", text: $medicationNotes, axis: .vertical).lineLimit(2...8)
                }
                Section("就诊信息") {
                    TextField("医院", text: $hospital); TextField("医生", text: $clinician)
                    TextField("其他备注", text: $notes, axis: .vertical).lineLimit(2...12)
                }
                if let fixedMemberId, fixedMemberId != store.currentMemberId {
                    Text("本页面仍保存到打开时选择的成员，防止资料串到其他成员。")
                        .font(.footnote).foregroundStyle(.orange)
                }
            }
            .navigationTitle(original == nil ? "新增病历" : "编辑病历")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("保存", action: save).disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }
            }
        }
        .onAppear {
            if let conditionId = original?.conditionId { conditionName = store.state.conditions.first(where: { $0.id == conditionId })?.name ?? "" }
        }
    }

    private func save() {
        var value = original ?? ClinicalRecord(memberId: fixedMemberId)
        value.recordDate = date; value.title = title; value.stage = stage; value.symptoms = symptoms
        value.diagnosis = diagnosis; value.treatment = treatment; value.medicationNotes = medicationNotes
        value.hospital = hospital; value.clinician = clinician; value.notes = notes
        let succeeded = original == nil ? store.addRecord(value, conditionName: conditionName) : store.updateRecord(value)
        if succeeded { dismiss() }
    }
}

struct RecordDetail: View {
    @EnvironmentObject private var store: HealthStore
    @EnvironmentObject private var lock: AppLockController
    @Environment(\.dismiss) private var dismiss
    let recordId: UUID
    @State private var importing = false
    @State private var editing = false
    @State private var deletingRecord = false
    @State private var deleteAttachment: Attachment?
    @State private var preview: PreviewFile?

    private var record: ClinicalRecord? { store.state.records.first { $0.id == recordId } }
    private var attachments: [Attachment] { store.state.attachments.filter { $0.recordId == recordId } }

    var body: some View {
        Group {
            if let record {
                List {
                    Section("病历") {
                        LabeledContent("日期", value: record.recordDate.formatted(date: .long, time: .omitted))
                        InlineField(label: "症状/病情", value: record.symptoms); InlineField(label: "诊断", value: record.diagnosis)
                        InlineField(label: "治疗方案", value: record.treatment); InlineField(label: "就诊用药记录", value: record.medicationNotes)
                        InlineField(label: "医院", value: record.hospital); InlineField(label: "医生", value: record.clinician)
                        InlineField(label: "其他备注", value: record.notes)
                    }
                    Section("检查报告") {
                        ForEach(attachments) { attachment in
                            HStack {
                                Button { if let url = try? AttachmentFiles.url(attachment) { preview = PreviewFile(url: url, kind: attachment.kind) } } label: {
                                    Label(attachment.displayName, systemImage: attachment.kind == "PDF" ? "doc.richtext" : "photo")
                                }
                                Spacer(); Button(role: .destructive) { deleteAttachment = attachment } label: { Image(systemName: "trash") }
                            }
                        }
                        Button("添加图片或 PDF") { lock.beginExternalPicker(); importing = true }
                    }
                    Section { Button("删除整条病历", role: .destructive) { deletingRecord = true } }
                }
                .navigationTitle(record.title).toolbar { Button("编辑") { editing = true } }
                .sheet(isPresented: $editing) { RecordEditor(original: record) }
            } else { EmptyStateView(title: "病历已不存在", systemImage: "doc.questionmark") }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.image, .pdf], allowsMultipleSelection: true) { result in
            lock.endExternalPicker()
            if case .success(let urls) = result { urls.forEach { store.addAttachment(recordId: recordId, source: $0) } }
            if case .failure(let error) = result { store.errorMessage = error.localizedDescription }
        }
        .onChange(of: importing) { if !$0 { lock.endExternalPicker() } }
        .sheet(item: $preview) { DocumentPreview(url: $0.url, kind: $0.kind) }
        .alert("删除检查报告？", isPresented: Binding(get: { deleteAttachment != nil }, set: { if !$0 { deleteAttachment = nil } })) {
            Button("取消", role: .cancel) { deleteAttachment = nil }
            Button("删除", role: .destructive) { if let value = deleteAttachment { store.deleteAttachment(value) }; deleteAttachment = nil }
        } message: { Text(deleteAttachment?.displayName ?? "") }
        .confirmationDialog("删除病历及其全部附件？此操作不可恢复。", isPresented: $deletingRecord, titleVisibility: .visible) {
            Button("删除", role: .destructive) { store.deleteRecord(recordId); dismiss() }; Button("取消", role: .cancel) {}
        }
    }
}

private struct PreviewFile: Identifiable { let url: URL; let kind: String; var id: String { url.path } }
private struct DocumentPreview: UIViewControllerRepresentable {
    let url: URL; let kind: String
    func makeUIViewController(context: Context) -> UIViewController {
        if kind == "PDF" { let controller = PDFViewController(); controller.url = url; return controller }
        return UIHostingController(rootView: Image(uiImage: UIImage(contentsOfFile: url.path) ?? UIImage()).resizable().scaledToFit())
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
private final class PDFViewController: UIViewController {
    var url: URL!
    override func viewDidLoad() {
        super.viewDidLoad(); let pdfView = PDFView(frame: view.bounds); pdfView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        pdfView.autoScales = true; pdfView.document = PDFDocument(url: url); view.addSubview(pdfView)
    }
}
