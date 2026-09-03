import { util } from '@kit.ArkTS';

export interface Condition { uuid: string; name: string; color: number; notes: string; archived: boolean; createdAt: string; updatedAt: string }
export interface ClinicalRecord { uuid: string; conditionUuid?: string; recordDate: string; title: string; stage: string; symptoms: string; diagnosis: string; treatment: string; medicationNotes: string; hospital: string; clinician: string; notes: string; createdAt: string; updatedAt: string }
export interface Attachment { uuid: string; recordUuid: string; kind: string; displayName: string; mimeType: string; relativePath: string; sizeBytes: number; sha256: string; createdAt: string; updatedAt: string }
export interface FollowUpSchedule { uuid: string; conditionUuid?: string; title: string; recurrenceType: string; interval: number; anchorDate: string; anchorDayOfMonth: number; weekday?: number; reminderTime: string; leadDays: number; nextDueDate: string; enabled: boolean; createdAt: string; updatedAt: string }
export interface FollowUpOccurrence { uuid: string; scheduleUuid: string; dueDate: string; status: string; completedAt?: string; createdAt: string; updatedAt: string }
export interface MedicationSchedule { uuid: string; medicationUuid: string; localTime: string; enabled: boolean; updatedAt: string }
export interface Medication { uuid: string; conditionUuid?: string; name: string; doseAmount: string; doseUnit: string; instructions: string; startDate: string; endDate?: string; mode: string; archived: boolean; createdAt: string; updatedAt: string }
export interface MedicationLog { uuid: string; medicationUuid: string; scheduleUuid?: string; scheduledAt: string; actualAt?: string; status: string; doseAmountSnapshot: string; doseUnitSnapshot: string; createdAt: string; updatedAt: string }
export interface HealthState { installationId: string; conditions: Condition[]; records: ClinicalRecord[]; attachments: Attachment[]; followUps: FollowUpSchedule[]; occurrences: FollowUpOccurrence[]; medications: Medication[]; medicationSchedules: MedicationSchedule[]; medicationLogs: MedicationLog[] }

export function emptyState(): HealthState {
  return { installationId: util.generateRandomUUID(true), conditions: [], records: [], attachments: [], followUps: [], occurrences: [], medications: [], medicationSchedules: [], medicationLogs: [] };
}
