import Foundation

struct Condition: Codable, Identifiable, Hashable {
    var id: UUID = UUID(); var name = ""; var color: UInt64 = 0xFF2F6B56; var notes = ""; var archived = false
    var createdAt = Date(); var updatedAt = Date()
}

struct ClinicalRecord: Codable, Identifiable, Hashable {
    var id: UUID = UUID(); var conditionId: UUID? = nil; var recordDate = Date(); var title = ""; var stage = "OTHER"
    var symptoms = ""; var diagnosis = ""; var treatment = ""; var medicationNotes = ""; var hospital = ""
    var clinician = ""; var notes = ""; var createdAt = Date(); var updatedAt = Date()
}

struct Attachment: Codable, Identifiable, Hashable {
    var id: UUID = UUID(); var recordId: UUID; var kind = "IMAGE"; var displayName = ""; var mimeType = ""
    var relativePath = ""; var sizeBytes: Int64 = 0; var sha256 = ""; var createdAt = Date(); var updatedAt = Date()
}

struct FollowUpSchedule: Codable, Identifiable, Hashable {
    var id: UUID = UUID(); var conditionId: UUID? = nil; var title = ""; var recurrenceType = "ONCE"; var interval = 1
    var anchorDate = Date(); var anchorDayOfMonth = 1; var weekday: Int? = nil; var reminderHour = 9; var reminderMinute = 0
    var leadDays = 0; var nextDueDate = Date(); var enabled = true; var createdAt = Date(); var updatedAt = Date()
}

struct FollowUpOccurrence: Codable, Identifiable, Hashable {
    var id: UUID = UUID(); var scheduleId: UUID; var dueDate = Date(); var status = "PENDING"; var completedAt: Date?
    var createdAt = Date(); var updatedAt = Date()
}

struct Medication: Codable, Identifiable, Hashable {
    var id: UUID = UUID(); var conditionId: UUID? = nil; var name = ""; var doseAmount = ""; var doseUnit = ""
    var instructions = ""; var startDate = Date(); var endDate: Date? = nil; var mode = "SCHEDULED"; var archived = false
    var times: [String] = []; var createdAt = Date(); var updatedAt = Date()
}

struct MedicationLog: Codable, Identifiable, Hashable {
    var id: UUID = UUID(); var medicationId: UUID; var scheduledAt: Date; var actualAt: Date?; var status: String
    var doseAmountSnapshot: String; var doseUnitSnapshot: String; var createdAt = Date(); var updatedAt = Date()
}

struct HealthState: Codable, Equatable {
    var installationId = UUID()
    var conditions: [Condition] = []
    var records: [ClinicalRecord] = []
    var attachments: [Attachment] = []
    var followUps: [FollowUpSchedule] = []
    var occurrences: [FollowUpOccurrence] = []
    var medications: [Medication] = []
    var medicationLogs: [MedicationLog] = []
}
