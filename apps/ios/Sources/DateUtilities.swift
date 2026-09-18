import Foundation

enum HealthDate {
    static var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.locale = Locale(identifier: "zh_CN")
        return value
    }

    static func day(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    static func localDateTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        return formatter.string(from: date)
    }

    static func instant(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }

    static func parseDay(_ value: String) -> Date? {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.isLenient = false
        return formatter.date(from: value)
    }

    static func parseLocalDateTime(_ value: String) -> Date? {
        for pattern in ["yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm"] {
            let formatter = DateFormatter()
            formatter.calendar = calendar
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.dateFormat = pattern
            formatter.isLenient = false
            if let parsed = formatter.date(from: value) { return parsed }
        }
        return nil
    }

    static func parseInstant(_ value: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractional.date(from: value) ?? ISO8601DateFormatter().date(from: value)
    }

    static func at(_ date: Date, time: String) -> Date? {
        let parts = time.split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2, (0...23).contains(parts[0]), (0...59).contains(parts[1]) else { return nil }
        return calendar.date(bySettingHour: parts[0], minute: parts[1], second: 0, of: date)
    }

    static func isWithinCourse(_ date: Date, medication: Medication) -> Bool {
        let day = calendar.startOfDay(for: date)
        guard day >= calendar.startOfDay(for: medication.startDate) else { return false }
        if let endDate = medication.endDate, day > calendar.startOfDay(for: endDate) { return false }
        return true
    }

    static func nextOrSameWeekday(from date: Date, weekdayMondayOne: Int) -> Date {
        let currentCalendarWeekday = calendar.component(.weekday, from: date)
        let currentMondayOne = currentCalendarWeekday == 1 ? 7 : currentCalendarWeekday - 1
        let offset = (weekdayMondayOne - currentMondayOne + 7) % 7
        return calendar.date(byAdding: .day, value: offset, to: date) ?? date
    }

    static func nextOccurrence(for schedule: FollowUpSchedule) -> Date? {
        guard schedule.recurrenceType != "ONCE" else { return nil }
        let after = calendar.startOfDay(for: schedule.nextDueDate)
        if schedule.recurrenceType == "EVERY_N_DAYS" {
            return calendar.date(byAdding: .day, value: schedule.interval, to: after)
        }
        if schedule.recurrenceType == "EVERY_N_WEEKS" {
            return calendar.date(byAdding: .day, value: schedule.interval * 7, to: after)
        }
        var parts = calendar.dateComponents([.year, .month], from: after)
        parts.day = 1
        guard let first = calendar.date(from: parts),
              let targetMonth = calendar.date(byAdding: .month, value: schedule.interval, to: first),
              let range = calendar.range(of: .day, in: .month, for: targetMonth) else { return nil }
        var target = calendar.dateComponents([.year, .month], from: targetMonth)
        target.day = min(schedule.anchorDayOfMonth, range.count)
        return calendar.date(from: target)
    }
}
