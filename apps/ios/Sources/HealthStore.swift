import Foundation
import SwiftUI

@MainActor
final class HealthStore: ObservableObject {
    @Published private(set) var state = HealthState()
    @Published var errorMessage: String?
    private var database: SecureDatabase?

    init() {
        do { database = try SecureDatabase(); state = try database?.load() ?? HealthState() }
        catch { errorMessage = error.localizedDescription }
    }

    @discardableResult
    func mutate(_ change: (inout HealthState) -> Void) -> Bool {
        guard errorMessage == nil else { return false }
        var candidate = state
        change(&candidate)
        do {
            guard let database else { throw StorageError.database("数据库未打开") }
            try database.save(candidate)
            state = candidate
            Task { await LocalReminderScheduler.rebuild(candidate) }
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func addRecord(_ record: ClinicalRecord) { mutate { $0.records.append(record) } }
    func deleteRecord(_ id: UUID) {
        let obsoleteFiles = state.attachments.filter { $0.recordId == id }
        if mutate({ value in value.records.removeAll { $0.id == id }; value.attachments.removeAll { $0.recordId == id } }) {
            obsoleteFiles.forEach { try? AttachmentFiles.delete($0) }
        }
    }
    func addAttachment(recordId: UUID, source: URL) {
        do {
            let value = try AttachmentFiles.importFile(recordId: recordId, source: source)
            if !mutate({ $0.attachments.append(value) }) { try? AttachmentFiles.delete(value) }
        }
        catch { errorMessage = error.localizedDescription }
    }
    func addFollowUp(_ value: FollowUpSchedule) { mutate { $0.followUps.append(value) } }
    func completeFollowUp(_ id: UUID, skipped: Bool = false) {
        mutate { value in
            guard let index = value.followUps.firstIndex(where: { $0.id == id }) else { return }
            let schedule = value.followUps[index]
            value.occurrences.append(FollowUpOccurrence(scheduleId: id, dueDate: schedule.nextDueDate, status: skipped ? "SKIPPED" : "DONE", completedAt: Date()))
            if let next = schedule.nextDate() { value.followUps[index].nextDueDate = next; value.followUps[index].updatedAt = Date() }
            else { value.followUps[index].enabled = false; value.followUps[index].updatedAt = Date() }
        }
    }
    func addMedication(_ value: Medication) { mutate { $0.medications.append(value) } }
    func markMedication(_ medication: Medication, status: String) {
        let calendar = Calendar.current
        let scheduled = calendar.date(bySettingHour: calendar.component(.hour, from: Date()), minute: calendar.component(.minute, from: Date()), second: 0, of: Date())!
        mutate { value in
            guard !value.medicationLogs.contains(where: {
                $0.medicationId == medication.id && calendar.isDate($0.scheduledAt, equalTo: scheduled, toGranularity: .minute)
            }) else { return }
            value.medicationLogs.append(MedicationLog(medicationId: medication.id, scheduledAt: scheduled, actualAt: status == "TAKEN" ? Date() : nil, status: status, doseAmountSnapshot: medication.doseAmount, doseUnitSnapshot: medication.doseUnit))
        }
    }
}

private extension FollowUpSchedule {
    func nextDate() -> Date? {
        guard recurrenceType != "ONCE" else { return nil }
        let calendar = Calendar(identifier: .gregorian)
        if recurrenceType == "EVERY_N_DAYS" { return calendar.date(byAdding: .day, value: interval, to: nextDueDate) }
        if recurrenceType == "EVERY_N_WEEKS" { return calendar.date(byAdding: .day, value: interval * 7, to: nextDueDate) }
        var parts = calendar.dateComponents([.year, .month], from: nextDueDate)
        parts.day = 1
        guard let first = calendar.date(from: parts), let targetMonth = calendar.date(byAdding: .month, value: interval, to: first),
              let range = calendar.range(of: .day, in: .month, for: targetMonth) else { return nil }
        var target = calendar.dateComponents([.year, .month], from: targetMonth)
        target.day = min(anchorDayOfMonth, range.count)
        return calendar.date(from: target)
    }
}
