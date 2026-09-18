import Foundation

struct FamilyMember: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var name = "本人"
    var relationship = "本人"
    var archived = false
    var createdAt = Date()
    var updatedAt = Date()

    var displayName: String { relationship.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? name : relationship }
}

struct Condition: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var memberId: UUID?
    var name = ""
    var color: UInt64 = 0xFF2F6B56
    var notes = ""
    var archived = false
    var createdAt = Date()
    var updatedAt = Date()
}

struct ClinicalRecord: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var memberId: UUID?
    var conditionId: UUID?
    var recordDate = Date()
    var dayOrder: Int64?
    var title = ""
    var stage = "OTHER"
    var symptoms = ""
    var diagnosis = ""
    var treatment = ""
    var medicationNotes = ""
    var hospital = ""
    var clinician = ""
    var notes = ""
    var createdAt = Date()
    var updatedAt = Date()
}

struct Attachment: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var recordId: UUID
    var kind = "IMAGE"
    var displayName = ""
    var mimeType = ""
    var relativePath = ""
    var sizeBytes: Int64 = 0
    var sha256 = ""
    var createdAt = Date()
    var updatedAt = Date()
}

struct FollowUpSchedule: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var memberId: UUID?
    var conditionId: UUID?
    var title = ""
    var recurrenceType = "ONCE"
    var interval = 1
    var anchorDate = Date()
    var anchorDayOfMonth = 1
    var weekday: Int?
    var reminderHour = 9
    var reminderMinute = 0
    var leadDays = 3
    var nextDueDate = Date()
    var enabled = true
    var createdAt = Date()
    var updatedAt = Date()
}

struct FollowUpOccurrence: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var scheduleId: UUID
    var dueDate = Date()
    var status = "PENDING"
    var completedAt: Date?
    var createdAt = Date()
    var updatedAt = Date()
}

struct Medication: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var memberId: UUID?
    var conditionId: UUID?
    var name = ""
    var doseAmount = ""
    var doseUnit = ""
    var instructions = ""
    var startDate = Date()
    var endDate: Date?
    var endedAt: Date?
    var archivedPreviousEndDate: Date?
    var mode = "SCHEDULED"
    var archived = false
    // 仅用于读取早期 iOS 原型数据。新版本使用 MedicationSchedule 保存历史计划。
    var times: [String] = []
    var createdAt = Date()
    var updatedAt = Date()
}

struct MedicationSchedule: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var medicationId: UUID
    var localTime = "08:00"
    var enabled = true
    var effectiveFrom = ""
    var effectiveTo: String?
    var doseAmountSnapshot = ""
    var doseUnitSnapshot = ""
    var updatedAt = Date()
    var pausedByCourseEnd = false
}

extension MedicationSchedule {
    private enum CodingKeys: String, CodingKey {
        case id, medicationId, localTime, enabled, effectiveFrom, effectiveTo, doseAmountSnapshot, doseUnitSnapshot, updatedAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
        medicationId = try container.decode(UUID.self, forKey: .medicationId)
        localTime = try container.decodeIfPresent(String.self, forKey: .localTime) ?? "08:00"
        enabled = try container.decodeIfPresent(Bool.self, forKey: .enabled) ?? true
        effectiveFrom = try container.decodeIfPresent(String.self, forKey: .effectiveFrom) ?? ""
        effectiveTo = try container.decodeIfPresent(String.self, forKey: .effectiveTo)
        doseAmountSnapshot = try container.decodeIfPresent(String.self, forKey: .doseAmountSnapshot) ?? ""
        doseUnitSnapshot = try container.decodeIfPresent(String.self, forKey: .doseUnitSnapshot) ?? ""
        updatedAt = try container.decodeIfPresent(Date.self, forKey: .updatedAt) ?? Date()
    }
}

struct MedicationLog: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var medicationId: UUID
    var scheduleId: UUID?
    var scheduledAt: Date
    var actualAt: Date?
    var status: String
    var doseAmountSnapshot: String
    var doseUnitSnapshot: String
    var createdAt = Date()
    var updatedAt = Date()
}

struct MedicationDayEntry: Identifiable, Hashable {
    let medication: Medication
    let schedule: MedicationSchedule?
    let log: MedicationLog?
    let plannedAt: Date?
    let actualAt: Date?
    let status: String

    var id: String {
        if let log { return "log-\(log.id.uuidString)" }
        if let schedule, let plannedAt { return "schedule-\(schedule.id.uuidString)-\(plannedAt.timeIntervalSince1970)" }
        return "prn-\(medication.id.uuidString)"
    }
}

struct HealthState: Codable, Equatable {
    var schemaVersion = 4
    var installationId = UUID()
    var selectedMemberId: UUID?
    var members: [FamilyMember] = []
    var conditions: [Condition] = []
    var records: [ClinicalRecord] = []
    var attachments: [Attachment] = []
    var followUps: [FollowUpSchedule] = []
    var occurrences: [FollowUpOccurrence] = []
    var medications: [Medication] = []
    var medicationSchedules: [MedicationSchedule] = []
    var medicationLogs: [MedicationLog] = []

    private enum CodingKeys: String, CodingKey {
        case schemaVersion, installationId, selectedMemberId, members, conditions, records, attachments
        case followUps, occurrences, medications, medicationSchedules, medicationLogs
    }

    init() {}

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try container.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 1
        installationId = try container.decodeIfPresent(UUID.self, forKey: .installationId) ?? UUID()
        selectedMemberId = try container.decodeIfPresent(UUID.self, forKey: .selectedMemberId)
        members = try container.decodeIfPresent([FamilyMember].self, forKey: .members) ?? []
        conditions = try container.decodeIfPresent([Condition].self, forKey: .conditions) ?? []
        records = try container.decodeIfPresent([ClinicalRecord].self, forKey: .records) ?? []
        attachments = try container.decodeIfPresent([Attachment].self, forKey: .attachments) ?? []
        followUps = try container.decodeIfPresent([FollowUpSchedule].self, forKey: .followUps) ?? []
        occurrences = try container.decodeIfPresent([FollowUpOccurrence].self, forKey: .occurrences) ?? []
        medications = try container.decodeIfPresent([Medication].self, forKey: .medications) ?? []
        medicationSchedules = try container.decodeIfPresent([MedicationSchedule].self, forKey: .medicationSchedules) ?? []
        medicationLogs = try container.decodeIfPresent([MedicationLog].self, forKey: .medicationLogs) ?? []
    }

    mutating func normalizeLegacyData() -> Bool {
        var changed = schemaVersion != 4
        schemaVersion = 4
        if members.isEmpty {
            let member = FamilyMember()
            members = [member]
            selectedMemberId = member.id
            changed = true
        }
        let fallback = selectedMemberId.flatMap { selected in members.first(where: { $0.id == selected && !$0.archived })?.id }
            ?? members.first(where: { !$0.archived })?.id
            ?? members[0].id
        if selectedMemberId != fallback { selectedMemberId = fallback; changed = true }
        for index in conditions.indices where conditions[index].memberId == nil { conditions[index].memberId = fallback; changed = true }
        for index in records.indices where records[index].memberId == nil { records[index].memberId = fallback; changed = true }
        for index in followUps.indices where followUps[index].memberId == nil { followUps[index].memberId = fallback; changed = true }
        for index in medications.indices where medications[index].memberId == nil { medications[index].memberId = fallback; changed = true }

        var nextOrder: [String: Int64] = [:]
        for record in records {
            guard let dayOrder = record.dayOrder else { continue }
            let key = "\(record.memberId?.uuidString ?? fallback.uuidString):\(Self.dayKey(record.recordDate))"
            nextOrder[key] = max(nextOrder[key, default: 0], dayOrder + 1)
        }
        let sorted = records.indices.sorted {
            if records[$0].recordDate != records[$1].recordDate { return records[$0].recordDate < records[$1].recordDate }
            if records[$0].updatedAt != records[$1].updatedAt { return records[$0].updatedAt > records[$1].updatedAt }
            return records[$0].id.uuidString < records[$1].id.uuidString
        }
        for index in sorted where records[index].dayOrder == nil {
            let key = "\(records[index].memberId?.uuidString ?? fallback.uuidString):\(Self.dayKey(records[index].recordDate))"
            records[index].dayOrder = nextOrder[key, default: 0]
            nextOrder[key, default: 0] += 1
            changed = true
        }
        if medicationSchedules.isEmpty {
            for medication in medications where medication.mode == "SCHEDULED" {
                for time in medication.times where Self.validTime(time) {
                    medicationSchedules.append(MedicationSchedule(
                        medicationId: medication.id,
                        localTime: time,
                        effectiveFrom: Self.dayKey(medication.startDate),
                        effectiveTo: medication.endDate.map(Self.dayKey),
                        doseAmountSnapshot: medication.doseAmount,
                        doseUnitSnapshot: medication.doseUnit,
                        updatedAt: medication.updatedAt
                    ))
                }
            }
            if !medicationSchedules.isEmpty { changed = true }
        }
        for index in medicationSchedules.indices {
            guard let medication = medications.first(where: { $0.id == medicationSchedules[index].medicationId }) else { continue }
            if medicationSchedules[index].effectiveFrom.isEmpty {
                medicationSchedules[index].effectiveFrom = Self.dayKey(medication.startDate)
                medicationSchedules[index].effectiveTo = medication.endDate.map(Self.dayKey)
                changed = true
            }
            if medicationSchedules[index].doseAmountSnapshot.isEmpty {
                medicationSchedules[index].doseAmountSnapshot = medication.doseAmount
                changed = true
            }
            if medicationSchedules[index].doseUnitSnapshot.isEmpty {
                medicationSchedules[index].doseUnitSnapshot = medication.doseUnit
                changed = true
            }
        }
        return changed
    }

    private static func dayKey(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    private static func validTime(_ value: String) -> Bool {
        value.range(of: #"^(?:[01]\d|2[0-3]):[0-5]\d$"#, options: .regularExpression) != nil
    }
}
