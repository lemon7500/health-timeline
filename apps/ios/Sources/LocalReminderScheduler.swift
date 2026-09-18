import Foundation
import UserNotifications

enum LocalReminderScheduler {
    static let pendingLimit = 60

    static func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound])) ?? false
    }

    static func authorizationStatus() async -> UNAuthorizationStatus {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    static func rebuild(_ state: HealthState, now: Date = Date()) async {
        let center = UNUserNotificationCenter.current()
        center.removeAllPendingNotificationRequests()
        let activeMembers = Set(state.members.filter { !$0.archived }.map(\.id))
        var requests: [(Date, UNNotificationRequest)] = []

        for followUp in state.followUps where followUp.enabled && followUp.memberId.map(activeMembers.contains) == true {
            guard let alertDay = HealthDate.calendar.date(byAdding: .day, value: -followUp.leadDays, to: followUp.nextDueDate),
                  let requested = HealthDate.calendar.date(bySettingHour: followUp.reminderHour, minute: followUp.reminderMinute, second: 0, of: alertDay) else { continue }
            let fireDate = max(requested, now.addingTimeInterval(15))
            requests.append((fireDate, request(
                id: "followup-\(followUp.id.uuidString)-\(HealthDate.day(followUp.nextDueDate))",
                fireDate: fireDate,
                body: "您有一项复查计划需要处理"
            )))
        }

        let medications = Dictionary(uniqueKeysWithValues: state.medications.map { ($0.id, $0) })
        let startDay = HealthDate.calendar.startOfDay(for: now)
        for offset in 0..<35 {
            guard let day = HealthDate.calendar.date(byAdding: .day, value: offset, to: startDay) else { continue }
            let dayKey = HealthDate.day(day)
            for schedule in state.medicationSchedules where schedule.enabled && schedule.effectiveFrom <= dayKey && (schedule.effectiveTo == nil || schedule.effectiveTo! >= dayKey) {
                guard let medication = medications[schedule.medicationId], !medication.archived,
                      medication.memberId.map(activeMembers.contains) == true,
                      HealthDate.isWithinCourse(day, medication: medication),
                      let fireDate = HealthDate.at(day, time: schedule.localTime), fireDate > now else { continue }
                requests.append((fireDate, request(
                    id: "medication-\(schedule.id.uuidString)-\(dayKey)",
                    fireDate: fireDate,
                    body: "您有一项用药计划需要处理"
                )))
            }
        }

        for (_, request) in requests.sorted(by: { $0.0 < $1.0 }).prefix(pendingLimit) {
            try? await center.add(request)
        }
    }

    private static func request(id: String, fireDate: Date, body: String) -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = "病程日历提醒"
        content.body = body
        content.sound = .default
        content.userInfo = ["healthTimelineReminder": true]
        let components = HealthDate.calendar.dateComponents([.year, .month, .day, .hour, .minute], from: fireDate)
        return UNNotificationRequest(identifier: id, content: content, trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false))
    }
}
