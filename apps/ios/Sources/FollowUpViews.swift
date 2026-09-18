import SwiftUI

struct FollowUpHome: View {
    @EnvironmentObject private var store: HealthStore
    @State private var adding = false

    private var schedules: [FollowUpSchedule] {
        guard let memberId = store.currentMemberId else { return [] }
        return store.state.followUps.filter { $0.memberId == memberId && $0.enabled }.sorted { $0.nextDueDate < $1.nextDueDate }
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(schedules) { value in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(value.title).font(.headline)
                        Text("下次：\(value.nextDueDate.formatted(date: .abbreviated, time: .omitted)) · 提前 \(value.leadDays) 天")
                            .font(.subheadline).foregroundStyle(.secondary)
                        HStack {
                            Button("完成") { store.completeFollowUp(value.id) }.buttonStyle(.borderedProminent)
                            Button("跳过") { store.completeFollowUp(value.id, skipped: true) }.buttonStyle(.bordered)
                        }
                    }.padding(.vertical, 4)
                }
            }
            .overlay { if schedules.isEmpty { EmptyStateView(title: "没有待复查项目", systemImage: "calendar.badge.checkmark") } }
            .navigationTitle("复查")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { MemberPicker() }
                ToolbarItem(placement: .navigationBarTrailing) { Button { adding = true } label: { Image(systemName: "plus") } }
            }
            .sheet(isPresented: $adding) { FollowUpEditor(memberId: store.currentMemberId) }
        }
    }
}

struct FollowUpEditor: View {
    @EnvironmentObject private var store: HealthStore
    @Environment(\.dismiss) private var dismiss
    let memberId: UUID?
    @State private var title = ""
    @State private var date = Date()
    @State private var interval = 1
    @State private var type = "EVERY_N_MONTHS"
    @State private var weekday = 4
    @State private var leadDays = 3
    @State private var hour = 9
    @State private var minute = 0

    var body: some View {
        NavigationStack {
            Form {
                Section("复查计划") {
                    TextField("复查标题", text: $title)
                    DatePicker("首次日期", selection: $date, displayedComponents: .date)
                    Picker("重复", selection: $type) {
                        Text("一次").tag("ONCE"); Text("每 N 天").tag("EVERY_N_DAYS")
                        Text("每 N 周").tag("EVERY_N_WEEKS"); Text("每 N 月").tag("EVERY_N_MONTHS")
                    }
                    if type != "ONCE" { Stepper("间隔：\(interval)", value: $interval, in: 1...365) }
                    if type == "EVERY_N_WEEKS" {
                        Picker("星期", selection: $weekday) {
                            ForEach(1...7, id: \.self) { Text(["一", "二", "三", "四", "五", "六", "日"][$0 - 1]).tag($0) }
                        }
                    }
                }
                Section("提醒") {
                    Picker("提前", selection: $leadDays) {
                        Text("当天").tag(0); Text("提前 1 天").tag(1); Text("提前 3 天").tag(3); Text("提前 7 天").tag(7)
                    }
                    FiveMinuteTimePicker(hour: $hour, minute: $minute)
                }
                if memberId != store.currentMemberId { Text("该计划保存到打开页面时选择的成员。") .font(.footnote).foregroundStyle(.orange) }
            }
            .navigationTitle("新增复查")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        let firstDue = type == "EVERY_N_WEEKS" ? HealthDate.nextOrSameWeekday(from: date, weekdayMondayOne: weekday) : date
                        let schedule = FollowUpSchedule(
                            memberId: memberId, title: title, recurrenceType: type, interval: interval,
                            anchorDate: date, anchorDayOfMonth: HealthDate.calendar.component(.day, from: date),
                            weekday: type == "EVERY_N_WEEKS" ? weekday : nil, reminderHour: hour,
                            reminderMinute: minute, leadDays: leadDays, nextDueDate: firstDue
                        )
                        if store.addFollowUp(schedule) { dismiss() }
                    }.disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
