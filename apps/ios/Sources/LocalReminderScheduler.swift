import Foundation
import UserNotifications

enum LocalReminderScheduler {
    static func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound])) ?? false
    }

    static func rebuild(_ state: HealthState) async {
        let center = UNUserNotificationCenter.current()
        center.removeAllPendingNotificationRequests()
        var requests: [(Date, UNNotificationRequest)] = []
        let calendar = Calendar.current
        for followUp in state.followUps where followUp.enabled {
            guard let alertDate = calendar.date(byAdding: .day, value: -followUp.leadDays, to: followUp.nextDueDate) else { continue }
            var components = calendar.dateComponents([.year, .month, .day], from: alertDate)
            components.hour = followUp.reminderHour; components.minute = followUp.reminderMinute
            let content = UNMutableNotificationContent(); content.title = "病程日历提醒"; content.body = "您有一项复查计划需要处理"; content.sound = .default
            requests.append((alertDate, UNNotificationRequest(identifier: "followup-\(followUp.id.uuidString)", content: content, trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false))))
        }
        for medicine in state.medications where !medicine.archived && medicine.mode == "SCHEDULED" {
            for time in medicine.times {
                let values = time.split(separator: ":").compactMap { Int($0) }
                guard values.count == 2 else { continue }
                let content = UNMutableNotificationContent(); content.title = "病程日历提醒"; content.body = "您有一项用药计划需要处理"; content.sound = .default
                let trigger = UNCalendarNotificationTrigger(dateMatching: DateComponents(hour: values[0], minute: values[1]), repeats: true)
                requests.append((Date(), UNNotificationRequest(identifier: "medicine-\(medicine.id.uuidString)-\(time)", content: content, trigger: trigger)))
            }
        }
        for (_, request) in requests.sorted(by: { $0.0 < $1.0 }).prefix(60) { try? await center.add(request) }
    }
}
