import Foundation

enum HealthDate {
    // Use Foundation's application default consistently. DateFormatter defaults
    // to this zone, while TimeZone.current can still report the system zone when
    // the application default is overridden (including simulator tests).
    static var localTimeZone: TimeZone { NSTimeZone.default }

    static var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.locale = Locale(identifier: "zh_CN")
        value.timeZone = localTimeZone
        return value
    }

    static func day(_ date: Date) -> String {
        formatter("yyyy-MM-dd", timeZone: localTimeZone).string(from: date)
    }

    static func localDateTime(_ date: Date) -> String {
        formatter("yyyy-MM-dd'T'HH:mm:ss", timeZone: localTimeZone).string(from: date)
    }

    static func instant(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }

    static func parseDay(_ value: String) -> Date? {
        guard canonicalDay(value) != nil else { return nil }
        return displayDate(value, pattern: "yyyy-MM-dd")
    }

    static func parseLocalDateTime(_ value: String) -> Date? {
        guard let canonical = canonicalLocalDateTime(value) else { return nil }
        return displayDate(canonical, pattern: "yyyy-MM-dd'T'HH:mm:ss")
    }

    // Validate calendar fields in UTC, independently of DST or skipped local dates.
    static func canonicalDay(_ value: String) -> String? {
        strictUTC(value, pattern: "yyyy-MM-dd") == nil ? nil : value
    }

    static func canonicalLocalDateTime(_ value: String) -> String? {
        let candidate = value.count == 16 ? value + ":00" : value
        return strictUTC(candidate, pattern: "yyyy-MM-dd'T'HH:mm:ss") == nil ? nil : candidate
    }

    private static func formatter(_ pattern: String, timeZone: TimeZone) -> DateFormatter {
        let formatter = DateFormatter()
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone
        formatter.dateFormat = pattern
        formatter.isLenient = false
        return formatter
    }

    private static func strictUTC(_ value: String, pattern: String) -> Date? {
        let formatter = formatter(pattern, timeZone: TimeZone(secondsFromGMT: 0)!)
        guard let date = formatter.date(from: value), formatter.string(from: date) == value else { return nil }
        return date
    }

    // Date is only a UI adapter. A DST gap may normalize its display; never encode
    // that adapted Date over the authoritative stored calendar/local-clock string.
    private static func displayDate(_ value: String, pattern: String) -> Date? {
        let local = formatter(pattern, timeZone: localTimeZone)
        if let date = local.date(from: value) { return date }
        local.isLenient = true
        return local.date(from: value) ?? strictUTC(value, pattern: pattern)
    }

    static func addingDays(_ count: Int, to day: String) -> String? {
        guard let date = strictUTC(day, pattern: "yyyy-MM-dd") else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        guard let next = calendar.date(byAdding: .day, value: count, to: date) else { return nil }
        return formatter("yyyy-MM-dd", timeZone: calendar.timeZone).string(from: next)
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
        let day = self.day(date)
        return day >= medication.$startDate && (medication.$endDate == nil || day <= medication.$endDate!)
    }

    static func nextOrSameWeekday(from date: Date, weekdayMondayOne: Int) -> Date {
        let currentCalendarWeekday = calendar.component(.weekday, from: date)
        let currentMondayOne = currentCalendarWeekday == 1 ? 7 : currentCalendarWeekday - 1
        let offset = (weekdayMondayOne - currentMondayOne + 7) % 7
        return calendar.date(byAdding: .day, value: offset, to: date) ?? date
    }

    static func nextOccurrence(for schedule: FollowUpSchedule) -> Date? {
        nextOccurrenceDay(for: schedule).flatMap(parseDay)
    }

    static func nextOccurrenceDay(for schedule: FollowUpSchedule) -> String? {
        guard schedule.recurrenceType != "ONCE" else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        guard schedule.interval > 0, let after = strictUTC(schedule.$nextDueDate, pattern: "yyyy-MM-dd") else { return nil }
        let formatter = formatter("yyyy-MM-dd", timeZone: calendar.timeZone)
        if schedule.recurrenceType == "EVERY_N_DAYS" {
            return calendar.date(byAdding: .day, value: schedule.interval, to: after).map(formatter.string(from:))
        }
        if schedule.recurrenceType == "EVERY_N_WEEKS" {
            guard schedule.interval <= Int.max / 7 else { return nil }
            return calendar.date(byAdding: .day, value: schedule.interval * 7, to: after).map(formatter.string(from:))
        }
        var parts = calendar.dateComponents([.year, .month], from: after)
        parts.day = 1
        guard let first = calendar.date(from: parts),
              let targetMonth = calendar.date(byAdding: .month, value: schedule.interval, to: first),
              let range = calendar.range(of: .day, in: .month, for: targetMonth) else { return nil }
        var target = calendar.dateComponents([.year, .month], from: targetMonth)
        target.day = min(schedule.anchorDayOfMonth, range.count)
        return calendar.date(from: target).map(formatter.string(from:))
    }
}

protocol StableTemporalPolicy {
    static func text(from date: Date) -> String
    static func canonical(_ text: String) -> String?
    static func display(_ text: String) -> Date?
}

enum CalendarDayPolicy: StableTemporalPolicy {
    static func text(from date: Date) -> String { HealthDate.day(date) }
    static func canonical(_ text: String) -> String? { HealthDate.canonicalDay(text) }
    static func display(_ text: String) -> Date? { HealthDate.parseDay(text) }
}

enum LocalWallTimePolicy: StableTemporalPolicy {
    static func text(from date: Date) -> String { HealthDate.localDateTime(date) }
    static func canonical(_ text: String) -> String? { HealthDate.canonicalLocalDateTime(text) }
    static func display(_ text: String) -> Date? { HealthDate.parseLocalDateTime(text) }
}

@propertyWrapper
struct StableTemporal<Policy: StableTemporalPolicy>: Codable, Hashable {
    private var text: String
    init(wrappedValue: Date) { text = Policy.text(from: wrappedValue) }
    var wrappedValue: Date {
        get { Policy.display(text)! }
        set { text = Policy.text(from: newValue) }
    }
    var projectedValue: String { text }
    mutating func assignCanonical(_ raw: String) throws {
        guard let canonical = Policy.canonical(raw) else { throw StorageError.corruptData }
        text = canonical
    }
    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let raw = try? container.decode(String.self), let canonical = Policy.canonical(raw) { text = canonical }
        else { text = Policy.text(from: try container.decode(Date.self)) }
    }
    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(text)
    }
}

@propertyWrapper
struct OptionalStableTemporal<Policy: StableTemporalPolicy>: Codable, Hashable {
    private var text: String?
    init(wrappedValue: Date? = nil) { text = wrappedValue.map(Policy.text(from:)) }
    var wrappedValue: Date? {
        get { text.flatMap(Policy.display) }
        set { text = newValue.map(Policy.text(from:)) }
    }
    var projectedValue: String? { text }
    mutating func assignCanonical(_ raw: String?) throws {
        guard let raw else { text = nil; return }
        guard let canonical = Policy.canonical(raw) else { throw StorageError.corruptData }
        text = canonical
    }
    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() { text = nil }
        else if let raw = try? container.decode(String.self), let canonical = Policy.canonical(raw) { text = canonical }
        else { text = Policy.text(from: try container.decode(Date.self)) }
    }
    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(text)
    }
}

extension KeyedDecodingContainer {
    // Synthesized Codable otherwise treats a wrapped optional as a required key.
    func decode<Policy: StableTemporalPolicy>(_ type: OptionalStableTemporal<Policy>.Type, forKey key: Key) throws -> OptionalStableTemporal<Policy> {
        try decodeIfPresent(type, forKey: key) ?? OptionalStableTemporal(wrappedValue: nil)
    }
}

typealias CalendarDay = StableTemporal<CalendarDayPolicy>
typealias OptionalCalendarDay = OptionalStableTemporal<CalendarDayPolicy>
typealias LocalWallTime = StableTemporal<LocalWallTimePolicy>
typealias OptionalLocalWallTime = OptionalStableTemporal<LocalWallTimePolicy>
