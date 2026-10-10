import Foundation
import UserNotifications

enum LocalReminderScheduler {
    static let pendingLimit = 60
    struct PlannedReminder: Equatable {
        let id: String
        let fireDate: Date
        let body: String
    }
    @MainActor private static var latestRequested: (HealthState, Date)?
    @MainActor private static var rebuilding = false

    static func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound])) ?? false
    }

    static func authorizationStatus() async -> UNAuthorizationStatus {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    @MainActor
    static func rebuild(_ state: HealthState, now: Date = Date()) async {
        latestRequested = (state, now)
        guard !rebuilding else { return }
        rebuilding = true
        defer { rebuilding = false }
        let center = UNUserNotificationCenter.current()
        // An add() await can yield to a new edit. Finish with the latest complete
        // snapshot, rather than allowing an older rebuild to resurrect reminders.
        while let (requestedState, requestedNow) = latestRequested {
            latestRequested = nil
            center.removeAllPendingNotificationRequests()
            for item in plannedReminders(requestedState, now: requestedNow) {
                if latestRequested != nil { break }
                try? await center.add(request(id: item.id, fireDate: item.fireDate, body: item.body))
            }
        }
    }

    static func plannedReminders(_ state: HealthState, now: Date) -> [PlannedReminder] {
        let activeMembers = Set(state.members.filter { !$0.archived }.map(\.id))
        var requests: [PlannedReminder] = []
        var seenSlots = Set<String>()

        for followUp in state.followUps where followUp.enabled && followUp.memberId.map(activeMembers.contains) == true {
            let due = followUp.$nextDueDate
            let id = "followup-\(followUp.id.uuidString)-\(due)"
            guard !state.occurrences.contains(where: { $0.scheduleId == followUp.id && $0.$dueDate == due && $0.status != "PENDING" }),
                  seenSlots.insert(id).inserted,
                  let alertDayKey = HealthDate.addingDays(-followUp.leadDays, to: due),
                  let alertDay = HealthDate.parseDay(alertDayKey),
                  let requested = HealthDate.calendar.date(bySettingHour: followUp.reminderHour, minute: followUp.reminderMinute, second: 0, of: alertDay) else { continue }
            let fireDate = max(requested, now.addingTimeInterval(15))
            requests.append(PlannedReminder(
                id: id,
                fireDate: fireDate,
                body: "您有一项复查计划需要处理"
            ))
        }

        let medications = state.medications.reduce(into: [UUID: Medication]()) { $0[$1.id] = $1 }
        let startDay = HealthDate.calendar.startOfDay(for: now)
        for offset in 0..<35 {
            guard let day = HealthDate.calendar.date(byAdding: .day, value: offset, to: startDay) else { continue }
            let dayKey = HealthDate.day(day)
            for schedule in state.medicationSchedules where schedule.enabled && schedule.effectiveFrom <= dayKey && (schedule.effectiveTo == nil || schedule.effectiveTo! >= dayKey) {
                guard let medication = medications[schedule.medicationId], !medication.archived,
                      medication.memberId.map(activeMembers.contains) == true,
                      HealthDate.isWithinCourse(day, medication: medication),
                      let fireDate = HealthDate.at(day, time: schedule.localTime), fireDate > now else { continue }
                let slot = "\(dayKey)T\(schedule.localTime)"
                let id = "medication-\(medication.id.uuidString)-\(slot)"
                guard !state.medicationLogs.contains(where: { $0.medicationId == medication.id && String($0.$scheduledAt.prefix(16)) == slot }),
                      seenSlots.insert(id).inserted else { continue }
                requests.append(PlannedReminder(
                    id: id,
                    fireDate: fireDate,
                    body: "您有一项用药计划需要处理"
                ))
            }
        }

        return Array(requests.sorted { $0.fireDate == $1.fireDate ? $0.id < $1.id : $0.fireDate < $1.fireDate }.prefix(pendingLimit))
    }

    private static func request(id: String, fireDate: Date, body: String) -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = "病程日历提醒"
        content.body = body
        content.sound = .default
        content.userInfo = ["healthTimelineReminder": true]
        let components = notificationComponents(for: fireDate)
        return UNNotificationRequest(identifier: id, content: content, trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false))
    }

    static func notificationComponents(for fireDate: Date) -> DateComponents {
        // A catch-up reminder is only seconds ahead of now; dropping seconds can
        // turn it into a trigger in the past within the same minute.
        HealthDate.calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: fireDate)
    }
}
