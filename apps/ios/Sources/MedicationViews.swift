import SwiftUI

struct FiveMinuteTimePicker: View {
    @Binding var hour: Int
    @Binding var minute: Int
    var body: some View {
        HStack {
            Text("时间"); Spacer()
            Picker("小时", selection: $hour) { ForEach(0..<24, id: \.self) { Text(String(format: "%02d", $0)).tag($0) } }
                .pickerStyle(.wheel).frame(width: 70, height: 90).clipped()
            Text(":")
            Picker("分钟", selection: $minute) { ForEach(Array(stride(from: 0, through: 55, by: 5)), id: \.self) { Text(String(format: "%02d", $0)).tag($0) } }
                .pickerStyle(.wheel).frame(width: 70, height: 90).clipped()
        }
    }
}

struct MedicationHome: View {
    @EnvironmentObject private var store: HealthStore
    @State private var month = Date()
    @State private var selectedDay = Date()
    @State private var adding = false
    @State private var editingMedication: Medication?
    @State private var action: DoseAction?
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 4), count: 7)

    private var monthDays: [Date?] {
        guard let interval = HealthDate.calendar.dateInterval(of: .month, for: month),
              let count = HealthDate.calendar.range(of: .day, in: .month, for: month)?.count else { return [] }
        let leading = (HealthDate.calendar.component(.weekday, from: interval.start) + 5) % 7
        return Array(repeating: nil, count: leading) + (0..<count).map { HealthDate.calendar.date(byAdding: .day, value: $0, to: interval.start) }
    }
    private var entries: [MedicationDayEntry] { store.medicationEntries(on: selectedDay) }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Button { changeMonth(-1) } label: { Image(systemName: "chevron.left") }
                        Spacer(); Text(month.formatted(.dateTime.year().month())).font(.headline); Spacer()
                        Button { changeMonth(1) } label: { Image(systemName: "chevron.right") }
                    }
                    LazyVGrid(columns: columns, spacing: 8) {
                        ForEach(["一", "二", "三", "四", "五", "六", "日"], id: \.self) { Text($0).font(.caption) }
                        ForEach(Array(monthDays.enumerated()), id: \.offset) { _, day in
                            if let day {
                                let dayEntries = store.medicationEntries(on: day)
                                Button {
                                    selectedDay = day
                                } label: {
                                    VStack(spacing: 2) {
                                        Text("\(HealthDate.calendar.component(.day, from: day))")
                                        if !dayEntries.isEmpty {
                                            Circle().fill(dayEntries.allSatisfy { $0.status == "TAKEN" } ? Color.green : Color.orange).frame(width: 6, height: 6)
                                        }
                                    }
                                    .frame(maxWidth: .infinity, minHeight: 36)
                                    .background(HealthDate.calendar.isDate(day, inSameDayAs: selectedDay) ? Color.accentColor.opacity(0.16) : Color.clear,
                                                in: RoundedRectangle(cornerRadius: 8))
                                }.buttonStyle(.plain)
                            } else { Color.clear.frame(height: 36) }
                        }
                    }
                }
                Section(selectedDay.formatted(date: .long, time: .omitted)) {
                    if entries.isEmpty { Text("当天没有用药计划或记录").foregroundStyle(.secondary) }
                    ForEach(entries) { entry in
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(entry.medication.name) \(entry.schedule?.doseAmountSnapshot ?? entry.log?.doseAmountSnapshot ?? entry.medication.doseAmount) \(entry.schedule?.doseUnitSnapshot ?? entry.log?.doseUnitSnapshot ?? entry.medication.doseUnit)").font(.headline)
                            if let planned = entry.plannedAt { Text("计划：\(planned.formatted(date: .omitted, time: .shortened))") }
                            if let actual = entry.actualAt { Text("实际：\(actual.formatted(date: .omitted, time: .shortened))") }
                            Text(statusText(entry.status)).foregroundStyle(entry.status == "TAKEN" ? .green : entry.status == "SKIPPED" ? .orange : .secondary)
                            if selectedDay <= Date() {
                                HStack {
                                    Button(entry.log == nil ? "已服" : "修正") { action = DoseAction(entry: entry, status: "TAKEN") }.buttonStyle(.borderedProminent)
                                    Button("跳过") { action = DoseAction(entry: entry, status: "SKIPPED") }.buttonStyle(.bordered)
                                }
                            }
                        }.padding(.vertical, 3)
                    }
                }
                Section("药物疗程") {
                    ForEach(store.medicationsForCurrentMember()) { medication in
                        VStack(alignment: .leading, spacing: 5) {
                            HStack {
                                Text(medication.name).font(.headline); Spacer()
                                Button("编辑") { editingMedication = medication }
                            }
                            Text("\(medication.doseAmount) \(medication.doseUnit) · \(medication.mode == "AS_NEEDED" ? "按需" : "按计划")")
                                .font(.caption).foregroundStyle(.secondary)
                            if medication.mode == "AS_NEEDED" && selectedDay <= Date() {
                                Button("补记按需用药") {
                                    action = DoseAction(entry: MedicationDayEntry(medication: medication, schedule: nil, log: nil, plannedAt: nil, actualAt: nil, status: "UNRECORDED"), status: "TAKEN")
                                }.buttonStyle(.bordered)
                            }
                        }
                    }
                }
            }
            .navigationTitle("用药日历")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { MemberPicker() }
                ToolbarItem(placement: .navigationBarTrailing) { Button { adding = true } label: { Image(systemName: "plus") } }
            }
            .sheet(isPresented: $adding) { MedicationEditor(memberId: store.currentMemberId) }
            .sheet(item: $editingMedication) { MedicationEditor(memberId: $0.memberId, original: $0) }
            .sheet(item: $action) { DoseEditor(action: $0, selectedDay: selectedDay) }
        }
    }

    private func changeMonth(_ delta: Int) {
        if let value = HealthDate.calendar.date(byAdding: .month, value: delta, to: month) {
            month = value; selectedDay = value
        }
    }
    private func statusText(_ value: String) -> String { value == "TAKEN" ? "已服" : value == "SKIPPED" ? "已跳过" : "未记录" }
}

private struct DoseAction: Identifiable {
    let entry: MedicationDayEntry
    let status: String
    var id: String { "\(entry.id)-\(status)" }
}

private struct DoseEditor: View {
    @EnvironmentObject private var store: HealthStore
    @Environment(\.dismiss) private var dismiss
    let action: DoseAction
    let selectedDay: Date
    @State private var hour: Int
    @State private var minute: Int

    init(action: DoseAction, selectedDay: Date) {
        self.action = action; self.selectedDay = selectedDay
        let value = action.entry.actualAt ?? Date()
        _hour = State(initialValue: HealthDate.calendar.component(.hour, from: value))
        _minute = State(initialValue: (HealthDate.calendar.component(.minute, from: value) / 5) * 5)
    }
    var body: some View {
        NavigationStack {
            Form {
                Text(action.entry.medication.name).font(.headline)
                if action.status == "TAKEN" { FiveMinuteTimePicker(hour: $hour, minute: $minute) }
                Text(action.entry.log == nil ? "将新增当天记录。" : "将修正现有记录，不会改变历史剂量快照。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            .navigationTitle(action.status == "TAKEN" ? "记录已服" : "记录跳过")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("确认", action: save) }
            }
        }
    }
    private func save() {
        let actual = action.status == "TAKEN" ? HealthDate.calendar.date(bySettingHour: hour, minute: minute, second: 0, of: selectedDay) : nil
        let success: Bool
        if let log = action.entry.log { success = store.correctDose(logId: log.id, status: action.status, actualAt: actual) }
        else if let planned = action.entry.plannedAt { success = store.recordDose(medication: action.entry.medication, schedule: action.entry.schedule, scheduledAt: planned, actualAt: actual, status: action.status) }
        else if action.entry.schedule == nil, let actual {
            success = store.recordDose(medication: action.entry.medication, schedule: nil, scheduledAt: actual, actualAt: actual, status: action.status)
        } else { success = false }
        if success { dismiss() }
    }
}

private struct MedicationEditor: View {
    @EnvironmentObject private var store: HealthStore
    @Environment(\.dismiss) private var dismiss
    let memberId: UUID?
    let original: Medication?
    @State private var name = ""
    @State private var amount = "1"
    @State private var unit = "片"
    @State private var instructions = ""
    @State private var startDate = Date()
    @State private var hasEndDate = false
    @State private var endDate = Date()
    @State private var asNeeded = false
    @State private var times: [TimeValue] = [TimeValue(hour: 8, minute: 0)]
    @State private var effectiveDate = HealthDate.calendar.date(byAdding: .day, value: 1, to: Date()) ?? Date()
    @State private var loadedExistingTimes = false

    init(memberId: UUID?, original: Medication? = nil) {
        self.memberId = memberId; self.original = original
        _name = State(initialValue: original?.name ?? "")
        _amount = State(initialValue: original?.doseAmount ?? "1")
        _unit = State(initialValue: original?.doseUnit ?? "片")
        _instructions = State(initialValue: original?.instructions ?? "")
        _startDate = State(initialValue: original?.startDate ?? Date())
        _hasEndDate = State(initialValue: original?.endDate != nil)
        _endDate = State(initialValue: original?.endDate ?? Date())
        _asNeeded = State(initialValue: original?.mode == "AS_NEEDED")
        _times = State(initialValue: [TimeValue(hour: 8, minute: 0)])
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("药物") {
                    TextField("药名", text: $name); TextField("剂量", text: $amount); TextField("单位", text: $unit)
                    TextField("服用说明", text: $instructions, axis: .vertical)
                    DatePicker("开始日期", selection: $startDate, displayedComponents: .date)
                    Toggle("设置结束日期", isOn: $hasEndDate)
                    if hasEndDate { DatePicker("结束日期", selection: $endDate, in: startDate..., displayedComponents: .date) }
                    Toggle("按需服用", isOn: $asNeeded)
                    if original != nil { DatePicker("新设置生效日期", selection: $effectiveDate, in: Date()..., displayedComponents: .date) }
                }
                if !asNeeded {
                    Section("每日时间（5 分钟一档）") {
                        ForEach($times) { $time in FiveMinuteTimePicker(hour: $time.hour, minute: $time.minute) }
                        .onDelete { times.remove(atOffsets: $0) }
                        Button("增加时间") { times.append(TimeValue(hour: 20, minute: 0)) }
                    }
                }
                if memberId != store.currentMemberId { Text("该药物保存到打开页面时选择的成员。") .font(.footnote).foregroundStyle(.orange) }
            }
            .navigationTitle(original == nil ? "新增用药" : "编辑用药")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        var medication = original ?? Medication(memberId: memberId)
                        medication.name = name; medication.doseAmount = amount; medication.doseUnit = unit
                        medication.instructions = instructions; medication.startDate = startDate
                        medication.endDate = hasEndDate ? endDate : nil; medication.mode = asNeeded ? "AS_NEEDED" : "SCHEDULED"
                        let values = times.map { String(format: "%02d:%02d", $0.hour, $0.minute) }
                        let succeeded = original == nil ? store.addMedication(medication, times: values) : store.updateMedication(medication, times: values, effectiveDate: effectiveDate)
                        if succeeded { dismiss() }
                    }.disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || amount.isEmpty || unit.isEmpty)
                }
            }
        }
        .onAppear {
            guard !loadedExistingTimes, let original else { return }
            let today = HealthDate.day(Date())
            let values = store.state.medicationSchedules.filter {
                $0.medicationId == original.id && $0.enabled && $0.effectiveFrom <= today && ($0.effectiveTo == nil || $0.effectiveTo! >= today)
            }.compactMap { value -> TimeValue? in
                let pieces = value.localTime.split(separator: ":").compactMap { Int($0) }
                return pieces.count == 2 ? TimeValue(hour: pieces[0], minute: (pieces[1] / 5) * 5) : nil
            }
            if !values.isEmpty { times = values }
            loadedExistingTimes = true
        }
    }
}

private struct TimeValue: Identifiable { let id = UUID(); var hour: Int; var minute: Int }
