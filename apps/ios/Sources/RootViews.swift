import SwiftUI

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

struct MemberPicker: View {
    @EnvironmentObject private var store: HealthStore

    var body: some View {
        if let selected = store.currentMemberId {
            Picker("当前成员", selection: Binding(get: { selected }, set: { store.selectMember($0) })) {
                ForEach(store.activeMembers) { Text($0.displayName).tag($0.id) }
            }
            .pickerStyle(.menu)
            .accessibilityLabel("切换家庭成员")
        }
    }
}

struct CalendarHome: View {
    @EnvironmentObject private var store: HealthStore
    @State private var month = Date()
    @State private var query = ""
    @State private var adding = false
    @State private var quickAdding = false
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 3), count: 7)

    private var days: [Date?] {
        guard let interval = HealthDate.calendar.dateInterval(of: .month, for: month),
              let count = HealthDate.calendar.range(of: .day, in: .month, for: month)?.count else { return [] }
        let leading = (HealthDate.calendar.component(.weekday, from: interval.start) + 5) % 7
        return Array(repeating: nil, count: leading) + (0..<count).map { HealthDate.calendar.date(byAdding: .day, value: $0, to: interval.start) }
    }

    private var memberRecords: [ClinicalRecord] { store.recordsForCurrentMember().sorted(by: recordOrder) }
    private var results: [ClinicalRecord] {
        let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return [] }
        let conditions = Dictionary(uniqueKeysWithValues: store.conditionsForCurrentMember(includeArchived: true).map { ($0.id, $0.name) })
        return memberRecords.filter { record in
            [record.title, record.conditionId.flatMap { conditions[$0] } ?? "", record.symptoms, record.diagnosis,
             record.treatment, record.medicationNotes, record.hospital, record.clinician, record.notes]
                .contains { $0.localizedCaseInsensitiveContains(clean) }
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 8) {
                HStack {
                    Button { changeMonth(-1) } label: { Image(systemName: "chevron.left") }
                    Spacer(); Text(month.formatted(.dateTime.year().month())).font(.title2.bold()); Spacer()
                    Button { changeMonth(1) } label: { Image(systemName: "chevron.right") }
                }
                TextField("搜索标题、病名、诊断、治疗、医生或备注", text: $query)
                    .textFieldStyle(.roundedBorder).textInputAutocapitalization(.never)
                if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { monthGrid } else { searchResults }
            }
            .padding()
            .navigationTitle("病程日历")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { MemberPicker() }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button("手动新增病历") { adding = true }
                        Button("快速录入多条病历") { quickAdding = true }
                    } label: { Image(systemName: "plus") }
                }
            }
            .sheet(isPresented: $adding) { RecordEditor(memberId: store.currentMemberId) }
            .sheet(isPresented: $quickAdding) { QuickEntryView(memberId: store.currentMemberId) }
            .navigationDestination(for: UUID.self) { RecordDetail(recordId: $0) }
        }
    }

    private var monthGrid: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 5) {
                ForEach(["一", "二", "三", "四", "五", "六", "日"], id: \.self) { Text($0).font(.caption) }
                ForEach(Array(days.enumerated()), id: \.offset) { _, day in
                    if let day {
                        let records = memberRecords.filter { HealthDate.calendar.isDate($0.recordDate, inSameDayAs: day) }
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(HealthDate.calendar.component(.day, from: day))").font(.caption)
                            ForEach(records.prefix(2)) { value in
                                NavigationLink(value: value.id) {
                                    Text(value.title).font(.caption2).lineLimit(2)
                                        .frame(maxWidth: .infinity, alignment: .leading).padding(3)
                                        .background(Color.teal.opacity(0.16), in: RoundedRectangle(cornerRadius: 5))
                                }.buttonStyle(.plain)
                            }
                            if records.count > 2 { Text("+\(records.count - 2)").font(.caption2) }
                            Spacer(minLength: 0)
                        }.frame(maxWidth: .infinity, minHeight: 76, alignment: .topLeading)
                    } else { Color.clear.frame(height: 76) }
                }
            }
        }
    }

    private var searchResults: some View {
        List(results) { record in
            NavigationLink(value: record.id) {
                VStack(alignment: .leading) {
                    Text(record.title)
                    Text(record.recordDate.formatted(date: .abbreviated, time: .omitted)).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .listStyle(.plain)
        .overlay { if results.isEmpty { EmptyStateView(title: "没有匹配的病历", systemImage: "magnifyingglass") } }
    }

    private func changeMonth(_ delta: Int) {
        if let value = HealthDate.calendar.date(byAdding: .month, value: delta, to: month) { month = value }
    }
    private func recordOrder(_ lhs: ClinicalRecord, _ rhs: ClinicalRecord) -> Bool {
        if lhs.recordDate != rhs.recordDate { return lhs.recordDate < rhs.recordDate }
        if lhs.dayOrder != rhs.dayOrder { return (lhs.dayOrder ?? 0) < (rhs.dayOrder ?? 0) }
        return lhs.id.uuidString < rhs.id.uuidString
    }
}

struct InlineField: View {
    let label: String
    let value: String
    var body: some View {
        if !value.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text(label).font(.caption).foregroundStyle(.secondary)
                Text(value).textSelection(.enabled)
            }
        }
    }
}

struct EmptyStateView: View {
    let title: String
    let systemImage: String
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: systemImage).font(.largeTitle).foregroundStyle(.secondary)
            Text(title).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
