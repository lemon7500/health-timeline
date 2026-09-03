import PDFKit
import SwiftUI
import UniformTypeIdentifiers

struct RootView: View {
    var body: some View {
        TabView {
            CalendarHome().tabItem { Label("日历", systemImage: "calendar") }
            FollowUpHome().tabItem { Label("复查", systemImage: "calendar.badge.clock") }
            MedicationHome().tabItem { Label("用药", systemImage: "pills") }
            SettingsHome().tabItem { Label("设置", systemImage: "gearshape") }
        }
    }
}

struct CalendarHome: View {
    @EnvironmentObject private var store: HealthStore
    @State private var month = Date()
    @State private var query = ""
    @State private var adding = false
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 3), count: 7)
    private var calendar: Calendar { Calendar(identifier: .gregorian) }
    private var days: [Date?] {
        let interval = calendar.dateInterval(of: .month, for: month)!
        let count = calendar.range(of: .day, in: .month, for: month)!.count
        let leading = (calendar.component(.weekday, from: interval.start) + 5) % 7
        return Array(repeating: nil, count: leading) + (0..<count).map { calendar.date(byAdding: .day, value: $0, to: interval.start) }
    }
    private var results: [ClinicalRecord] {
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else { return [] }
        let conditionNames = Dictionary(uniqueKeysWithValues: store.state.conditions.map { ($0.id, $0.name) })
        return store.state.records.filter { record in
            [record.title, record.conditionId.flatMap { conditionNames[$0] } ?? "", record.symptoms, record.diagnosis,
             record.treatment, record.medicationNotes, record.hospital, record.clinician, record.notes]
                .contains { $0.localizedCaseInsensitiveContains(query) }
        }.sorted { $0.recordDate > $1.recordDate }
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 8) {
                HStack { Button { month = calendar.date(byAdding: .month, value: -1, to: month)! } label: { Image(systemName: "chevron.left") }; Spacer(); Text(month.formatted(.dateTime.year().month())).font(.title2.bold()); Spacer(); Button { month = calendar.date(byAdding: .month, value: 1, to: month)! } label: { Image(systemName: "chevron.right") } }
                TextField("搜索病历、诊断、治疗或医院", text: $query).textFieldStyle(.roundedBorder)
                if query.isEmpty {
                    LazyVGrid(columns: columns, spacing: 5) {
                        ForEach(["一","二","三","四","五","六","日"], id: \.self) { Text($0).font(.caption) }
                        ForEach(Array(days.enumerated()), id: \.offset) { _, day in
                            if let day {
                                let records = store.state.records.filter { calendar.isDate($0.recordDate, inSameDayAs: day) }
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("\(calendar.component(.day, from: day))").font(.caption)
                                    ForEach(records.prefix(2)) { value in
                                        NavigationLink(destination: RecordDetail(record: value)) {
                                            Text(value.title).font(.caption2).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading).padding(3).background(Color.teal.opacity(0.16), in: RoundedRectangle(cornerRadius: 5))
                                        }.buttonStyle(.plain)
                                    }
                                    if records.count > 2 { Text("+\(records.count - 2)").font(.caption2) }
                                    Spacer(minLength: 0)
                                }.frame(maxWidth: .infinity, minHeight: 74, alignment: .topLeading)
                            } else { Color.clear.frame(height: 74) }
                        }
                    }
                } else {
                    List(results) { record in NavigationLink(destination: RecordDetail(record: record)) { VStack(alignment: .leading) { Text(record.title); Text(record.recordDate.formatted(date: .abbreviated, time: .omitted)).font(.caption).foregroundStyle(.secondary) } } }
                }
            }.padding().navigationTitle("病程日历").toolbar { Button { adding = true } label: { Image(systemName: "plus") } }
                .sheet(isPresented: $adding) { RecordEditor() }
        }
    }
}

struct RecordEditor: View {
    @EnvironmentObject private var store: HealthStore; @Environment(\.dismiss) private var dismiss
    @State private var date = Date(); @State private var title = ""; @State private var diagnosis = ""; @State private var treatment = ""; @State private var notes = ""
    var body: some View { NavigationStack { Form { DatePicker("日期", selection: $date, displayedComponents: .date); TextField("标题", text: $title); TextField("诊断", text: $diagnosis, axis: .vertical); TextField("治疗", text: $treatment, axis: .vertical); TextField("备注", text: $notes, axis: .vertical) }.navigationTitle("新增病历").toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button("保存") { store.addRecord(ClinicalRecord(recordDate: date, title: title, diagnosis: diagnosis, treatment: treatment, notes: notes)); dismiss() }.disabled(title.trimmingCharacters(in: .whitespaces).isEmpty) } } } }
}

struct RecordDetail: View {
    @EnvironmentObject private var store: HealthStore; let record: ClinicalRecord; @State private var importing = false; @State private var preview: PreviewFile?
    var body: some View { List { Section("病历") { LabeledContent("日期", value: record.recordDate.formatted(date: .long, time: .omitted)); if !record.diagnosis.isEmpty { Text("诊断：\(record.diagnosis)") }; if !record.treatment.isEmpty { Text("治疗：\(record.treatment)") }; if !record.notes.isEmpty { Text("备注：\(record.notes)") } }; Section("检查报告") { ForEach(store.state.attachments.filter { $0.recordId == record.id }) { attachment in Button(attachment.displayName) { if let url = try? AttachmentFiles.url(attachment) { preview = PreviewFile(url: url, kind: attachment.kind) } } }; Button("添加图片或 PDF") { importing = true } } }.navigationTitle(record.title).fileImporter(isPresented: $importing, allowedContentTypes: [.image, .pdf], allowsMultipleSelection: true) { result in if case .success(let urls) = result { urls.forEach { store.addAttachment(recordId: record.id, source: $0) } } }.sheet(item: $preview) { item in DocumentPreview(url: item.url, kind: item.kind) } }
}
private struct PreviewFile: Identifiable { let url: URL; let kind: String; var id: String { url.path } }
private struct DocumentPreview: UIViewControllerRepresentable { let url: URL; let kind: String; func makeUIViewController(context: Context) -> UIViewController { if kind == "PDF" { let controller = PDFViewController(); controller.url = url; return controller }; let controller = UIHostingController(rootView: Image(uiImage: UIImage(contentsOfFile: url.path) ?? UIImage()).resizable().scaledToFit()); return controller }; func updateUIViewController(_ uiViewController: UIViewController, context: Context) {} }
private final class PDFViewController: UIViewController { var url: URL!; override func viewDidLoad() { super.viewDidLoad(); let view = PDFView(frame: self.view.bounds); view.autoresizingMask = [.flexibleWidth,.flexibleHeight]; view.autoScales = true; view.document = PDFDocument(url: url); self.view.addSubview(view) } }

struct FollowUpHome: View {
    @EnvironmentObject private var store: HealthStore; @State private var adding = false
    var body: some View { NavigationStack { List { ForEach(store.state.followUps.filter(\.enabled)) { value in VStack(alignment: .leading) { Text(value.title).font(.headline); Text("下次：\(value.nextDueDate.formatted(date: .abbreviated, time: .omitted))"); HStack { Button("完成") { store.completeFollowUp(value.id) }; Button("跳过") { store.completeFollowUp(value.id, skipped: true) } } } } }.navigationTitle("复查").toolbar { Button { adding = true } label: { Image(systemName: "plus") } }.sheet(isPresented: $adding) { FollowUpEditor() } } }
}
struct FollowUpEditor: View {
    @EnvironmentObject private var store: HealthStore; @Environment(\.dismiss) private var dismiss; @State private var title=""; @State private var date=Date(); @State private var interval=1; @State private var type="EVERY_N_MONTHS"
    var body: some View { NavigationStack { Form { TextField("复查标题", text: $title); DatePicker("首次日期", selection: $date, displayedComponents: .date); Picker("重复", selection: $type) { Text("一次").tag("ONCE"); Text("每 N 天").tag("EVERY_N_DAYS"); Text("每 N 周").tag("EVERY_N_WEEKS"); Text("每 N 月").tag("EVERY_N_MONTHS") }; Stepper("间隔 \(interval)", value: $interval, in: 1...365) }.navigationTitle("新增复查").toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button("保存") { store.addFollowUp(FollowUpSchedule(title: title, recurrenceType: type, interval: interval, anchorDate: date, anchorDayOfMonth: Calendar.current.component(.day, from: date), nextDueDate: date)); dismiss() }.disabled(title.isEmpty) } } } }
}

struct MedicationHome: View {
    @EnvironmentObject private var store: HealthStore; @State private var adding=false
    var body: some View { NavigationStack { List { ForEach(store.state.medications.filter { !$0.archived }) { medicine in VStack(alignment: .leading) { Text(medicine.name).font(.headline); Text("\(medicine.doseAmount) \(medicine.doseUnit) · \(medicine.times.joined(separator: "、"))"); HStack { Button("已服") { store.markMedication(medicine, status: "TAKEN") }; Button("跳过") { store.markMedication(medicine, status: "SKIPPED") } } } } }.navigationTitle("用药").toolbar { Button { adding=true } label: { Image(systemName:"plus") } }.sheet(isPresented:$adding) { MedicationEditor() } } }
}
struct MedicationEditor: View {
    @EnvironmentObject private var store: HealthStore; @Environment(\.dismiss) private var dismiss; @State private var name=""; @State private var amount="1"; @State private var unit="片"; @State private var times="08:00,20:00"
    var body: some View { NavigationStack { Form { TextField("药名", text:$name); TextField("剂量", text:$amount); TextField("单位", text:$unit); TextField("时间（逗号分隔）", text:$times) }.navigationTitle("新增用药").toolbar { ToolbarItem(placement:.cancellationAction){Button("取消"){dismiss()}}; ToolbarItem(placement:.confirmationAction){Button("保存"){ let parsed=times.split(separator:",").map{String($0).trimmingCharacters(in:.whitespaces)}.filter{!$0.isEmpty}; store.addMedication(Medication(name:name,doseAmount:amount,doseUnit:unit,times:parsed)); dismiss() }.disabled(name.isEmpty)} } } }
}

struct SettingsHome: View {
    @EnvironmentObject private var store: HealthStore
    var body: some View { NavigationStack { Form { Section("隐私") { Text("全部资料仅保存在本机；应用不申请网络权限。数据库密钥保存在 Keychain，附件受系统 Data Protection 保护。") }; Section("提醒") { Button("申请通知权限") { Task { _ = await LocalReminderScheduler.requestPermission(); await LocalReminderScheduler.rebuild(store.state) } } }; Section("备份") { Text("跨平台 .htbackup v2 的协议与合并规则位于 shared/spec；正式签名前必须通过黄金备份互操作测试。") }; Section("说明") { Text("本应用仅用于记录和提醒，不提供诊断、处方或药物安全建议。").foregroundStyle(.red) } }.navigationTitle("设置") } }
}
