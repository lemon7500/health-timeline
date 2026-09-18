import { Attachment, ClinicalRecord, Condition, FamilyMember, FollowUpOccurrence, FollowUpSchedule, HealthState, Medication, MedicationLog, MedicationSchedule, normalizeState, validateState } from '../model/Models';

export interface BackupConflict {
  entity: string;
  uuid: string;
  key: string;
}

export interface BackupPreview {
  additions: number;
  updates: number;
  duplicates: number;
  conflicts: BackupConflict[];
}

interface UuidValue { uuid: string }

export class BackupMerge {
  static preview(local: HealthState, imported: HealthState): BackupPreview {
    const result: BackupPreview = { additions: 0, updates: 0, duplicates: 0, conflicts: [] };
    this.count(local.members, imported.members, 'member', result);
    this.count(local.conditions, imported.conditions, 'condition', result);
    this.count(local.records, imported.records, 'record', result);
    this.countAttachments(local.attachments, imported.attachments, result);
    this.count(local.followUps, imported.followUps, 'followup', result);
    this.count(local.occurrences, imported.occurrences, 'occurrence', result);
    this.count(local.medications, imported.medications, 'medication', result);
    this.count(local.medicationSchedules, imported.medicationSchedules, 'medicationSchedule', result);
    this.count(local.medicationLogs, imported.medicationLogs, 'medicationLog', result);
    return result;
  }

  static merge(local: HealthState, imported: HealthState, useImported: Set<string> = new Set<string>()): HealthState {
    const result = JSON.parse(JSON.stringify(local)) as HealthState;
    result.members = this.mergeRows(local.members, imported.members, 'member', useImported) as FamilyMember[];
    result.conditions = this.mergeRows(local.conditions, imported.conditions, 'condition', useImported) as Condition[];
    result.records = this.mergeRows(local.records, imported.records, 'record', useImported) as ClinicalRecord[];
    result.attachments = this.mergeRows(local.attachments, imported.attachments, 'attachment', useImported) as Attachment[];
    result.followUps = this.mergeRows(local.followUps, imported.followUps, 'followup', useImported) as FollowUpSchedule[];
    result.occurrences = this.mergeRows(local.occurrences, imported.occurrences, 'occurrence', useImported) as FollowUpOccurrence[];
    result.medications = this.mergeRows(local.medications, imported.medications, 'medication', useImported) as Medication[];
    result.medicationSchedules = this.mergeRows(local.medicationSchedules, imported.medicationSchedules, 'medicationSchedule', useImported) as MedicationSchedule[];
    result.medicationLogs = this.mergeRows(local.medicationLogs, imported.medicationLogs, 'medicationLog', useImported) as MedicationLog[];
    result.installationId = local.installationId;
    result.selectedMemberUuid = local.selectedMemberUuid;
    normalizeState(result);
    validateState(result);
    return result;
  }

  private static count<T extends UuidValue>(local: T[], imported: T[], entity: string, result: BackupPreview): void {
    const localById = new Map<string, T>(local.map(item => [item.uuid, item]));
    imported.forEach(item => {
      const existing = localById.get(item.uuid);
      if (!existing) { result.additions++; return; }
      if (this.same(existing, item)) result.duplicates++;
      else { result.updates++; result.conflicts.push({ entity: entity, uuid: item.uuid, key: `${entity}:${item.uuid}` }); }
    });
  }

  private static countAttachments(local: Attachment[], imported: Attachment[], result: BackupPreview): void {
    const localById = new Map<string, Attachment>(local.map(item => [item.uuid, item]));
    imported.forEach(item => {
      const existing = localById.get(item.uuid);
      if (!existing) { result.additions++; return; }
      const equal = existing.recordUuid === item.recordUuid && existing.kind === item.kind &&
        existing.displayName === item.displayName && existing.mimeType === item.mimeType &&
        existing.sizeBytes === item.sizeBytes && existing.sha256 === item.sha256;
      if (equal) result.duplicates++;
      else { result.updates++; result.conflicts.push({ entity: 'attachment', uuid: item.uuid, key: `attachment:${item.uuid}` }); }
    });
  }

  private static mergeRows<T extends UuidValue>(local: T[], imported: T[], entity: string, useImported: Set<string>): T[] {
    const result = local.map(item => JSON.parse(JSON.stringify(item)) as T);
    const indexes = new Map<string, number>(result.map((item, index) => [item.uuid, index]));
    imported.forEach(item => {
      const index = indexes.get(item.uuid);
      if (index === undefined) {
        indexes.set(item.uuid, result.length);
        result.push(JSON.parse(JSON.stringify(item)) as T);
      } else if (useImported.has(`${entity}:${item.uuid}`)) {
        result[index] = JSON.parse(JSON.stringify(item)) as T;
      }
    });
    return result;
  }

  private static same<T>(lhs: T, rhs: T): boolean {
    return JSON.stringify(lhs) === JSON.stringify(rhs);
  }
}
