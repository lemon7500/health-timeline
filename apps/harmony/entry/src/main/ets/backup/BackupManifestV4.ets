import { ClinicalRecord, Condition, FamilyMember, FollowUpOccurrence, FollowUpSchedule, HealthState, Medication, MedicationLog, MedicationSchedule, validateState } from '../model/Models';

export interface PortableAttachment { uuid: string; recordUuid: string; kind: string; displayName: string; mimeType: string; archivePath: string; sizeBytes: number; sha256: string; createdAt: string; updatedAt: string }
export interface PortableSnapshotV4 {
  schemaVersion: number;
  exportedAt: string;
  sourcePlatform: string;
  sourceInstallationId: string;
  members: FamilyMember[];
  conditions: Condition[];
  records: ClinicalRecord[];
  attachments: PortableAttachment[];
  followUps: FollowUpSchedule[];
  occurrences: FollowUpOccurrence[];
  medications: Medication[];
  medicationSchedules: MedicationSchedule[];
  medicationLogs: MedicationLog[];
}

export class BackupManifestV4 {
  static fromState(state: HealthState): PortableSnapshotV4 {
    validateState(state);
    const attachments: PortableAttachment[] = state.attachments.map(item => ({
      uuid: item.uuid, recordUuid: item.recordUuid, kind: item.kind, displayName: item.displayName,
      mimeType: item.mimeType, archivePath: `files/${item.uuid.toLowerCase()}`,
      sizeBytes: item.sizeBytes, sha256: item.sha256, createdAt: item.createdAt, updatedAt: item.updatedAt
    }));
    const result: PortableSnapshotV4 = {
      schemaVersion: 4, exportedAt: new Date().toISOString(), sourcePlatform: 'harmony',
      sourceInstallationId: state.installationId, members: state.members, conditions: state.conditions,
      records: state.records.slice().sort((a, b) => a.recordDate === b.recordDate ? a.dayOrder - b.dayOrder : a.recordDate.localeCompare(b.recordDate)),
      attachments: attachments, followUps: state.followUps, occurrences: state.occurrences,
      medications: state.medications, medicationSchedules: state.medicationSchedules, medicationLogs: state.medicationLogs
    };
    this.validate(result);
    return result;
  }

  static validate(value: PortableSnapshotV4): void {
    if (value.schemaVersion !== 4 || !['android', 'ios', 'harmony', 'test'].includes(value.sourcePlatform)) throw new Error('不支持的备份版本或来源');
    if (Number.isNaN(new Date(value.exportedAt).getTime()) || value.members.length === 0) throw new Error('备份时间或成员异常');
    const attachmentPaths = new Set<string>();
    value.attachments.forEach(item => {
      const expected = `files/${item.uuid.toLowerCase()}`;
      if (item.archivePath.toLowerCase() !== expected || attachmentPaths.has(expected) || item.sizeBytes < 0 || item.sizeBytes > 104857600 || !/^[0-9a-f]{64}$/.test(item.sha256)) throw new Error('附件清单异常');
      attachmentPaths.add(expected);
    });
    const state: HealthState = {
      schemaVersion: 4, installationId: value.sourceInstallationId,
      selectedMemberUuid: value.members.find(item => !item.archived)?.uuid ?? value.members[0].uuid,
      members: value.members, conditions: value.conditions, records: value.records,
      attachments: value.attachments.map(item => ({ uuid: item.uuid, recordUuid: item.recordUuid, kind: item.kind, displayName: item.displayName, mimeType: item.mimeType, relativePath: `restored/${item.recordUuid}/${item.uuid}`, sizeBytes: item.sizeBytes, sha256: item.sha256, createdAt: item.createdAt, updatedAt: item.updatedAt })),
      followUps: value.followUps, occurrences: value.occurrences, medications: value.medications,
      medicationSchedules: value.medicationSchedules, medicationLogs: value.medicationLogs
    };
    validateState(state);
  }
}
