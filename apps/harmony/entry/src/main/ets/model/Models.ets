import { util } from '@kit.ArkTS';

export interface FamilyMember { uuid: string; name: string; nickname: string; relationship: string; archived: boolean; createdAt: string; updatedAt: string }
export interface Condition { uuid: string; memberUuid: string; name: string; color: number; notes: string; archived: boolean; createdAt: string; updatedAt: string }
export interface ClinicalRecord { uuid: string; memberUuid: string; conditionUuid?: string; recordDate: string; dayOrder: number; title: string; stage: string; symptoms: string; diagnosis: string; treatment: string; medicationNotes: string; hospital: string; clinician: string; notes: string; createdAt: string; updatedAt: string }
export interface Attachment { uuid: string; recordUuid: string; kind: string; displayName: string; mimeType: string; relativePath: string; sizeBytes: number; sha256: string; createdAt: string; updatedAt: string }
export interface FollowUpSchedule { uuid: string; memberUuid: string; conditionUuid?: string; title: string; recurrenceType: string; interval: number; anchorDate: string; anchorDayOfMonth: number; weekday?: number; reminderTime: string; leadDays: number; nextDueDate: string; enabled: boolean; createdAt: string; updatedAt: string }
export interface FollowUpOccurrence { uuid: string; scheduleUuid: string; dueDate: string; status: string; completedAt?: string; createdAt: string; updatedAt: string }
export interface MedicationSchedule { uuid: string; medicationUuid: string; localTime: string; enabled: boolean; effectiveFrom: string; effectiveTo?: string; doseAmountSnapshot: string; doseUnitSnapshot: string; updatedAt: string; pausedByCourseEnd?: boolean }
export interface Medication { uuid: string; memberUuid: string; conditionUuid?: string; name: string; doseAmount: string; doseUnit: string; instructions: string; startDate: string; endDate?: string; mode: string; archived: boolean; createdAt: string; updatedAt: string; endedAt?: string; archivedPreviousEndDate?: string }
export interface MedicationLog { uuid: string; medicationUuid: string; scheduleUuid?: string; scheduledAt: string; actualAt?: string; status: string; doseAmountSnapshot: string; doseUnitSnapshot: string; createdAt: string; updatedAt: string }
export interface HealthState { schemaVersion: number; installationId: string; selectedMemberUuid: string; members: FamilyMember[]; conditions: Condition[]; records: ClinicalRecord[]; attachments: Attachment[]; followUps: FollowUpSchedule[]; occurrences: FollowUpOccurrence[]; medications: Medication[]; medicationSchedules: MedicationSchedule[]; medicationLogs: MedicationLog[] }

export function newMember(name: string = '本人', relationship: string = '本人'): FamilyMember {
  const now = new Date().toISOString();
  return { uuid: util.generateRandomUUID(true), name: name, nickname: relationship, relationship: relationship, archived: false, createdAt: now, updatedAt: now };
}

export function emptyState(): HealthState {
  const member = newMember();
  return { schemaVersion: 4, installationId: util.generateRandomUUID(true), selectedMemberUuid: member.uuid, members: [member], conditions: [], records: [], attachments: [], followUps: [], occurrences: [], medications: [], medicationSchedules: [], medicationLogs: [] };
}

export function normalizeState(value: HealthState): HealthState {
  const fallback = emptyState();
  if (!value.installationId) value.installationId = fallback.installationId;
  if (!value.members || value.members.length === 0) value.members = fallback.members;
  const active = value.members.find(member => !member.archived) ?? value.members[0];
  if (!value.selectedMemberUuid || !value.members.some(member => member.uuid === value.selectedMemberUuid && !member.archived)) value.selectedMemberUuid = active.uuid;
  value.members.forEach(member => { member.relationship = member.relationship || member.nickname || member.name; member.nickname = member.relationship; });
  value.conditions = value.conditions || []; value.records = value.records || []; value.attachments = value.attachments || [];
  value.followUps = value.followUps || []; value.occurrences = value.occurrences || []; value.medications = value.medications || [];
  value.medicationSchedules = value.medicationSchedules || []; value.medicationLogs = value.medicationLogs || [];
  value.conditions.forEach(item => { if (!item.memberUuid) item.memberUuid = active.uuid; });
  const orderByDay: Map<string, number> = new Map();
  value.records.forEach(item => {
    if (!item.memberUuid) item.memberUuid = active.uuid;
    if (item.dayOrder !== undefined && item.dayOrder !== null) {
      const key = `${item.memberUuid}:${item.recordDate}`;
      orderByDay.set(key, Math.max(orderByDay.get(key) ?? 0, item.dayOrder + 1));
    }
  });
  value.records.forEach(item => {
    const key = `${item.memberUuid}:${item.recordDate}`;
    if (item.dayOrder === undefined || item.dayOrder === null) item.dayOrder = orderByDay.get(key) ?? 0;
    orderByDay.set(key, Math.max(orderByDay.get(key) ?? 0, item.dayOrder + 1));
  });
  value.followUps.forEach(item => { if (!item.memberUuid) item.memberUuid = active.uuid; if (item.leadDays === undefined) item.leadDays = 3; });
  value.medications.forEach(item => { if (!item.memberUuid) item.memberUuid = active.uuid; });
  const medicationById: Map<string, Medication> = new Map(value.medications.map(item => [item.uuid, item]));
  value.medicationSchedules.forEach(item => {
    const medication = medicationById.get(item.medicationUuid);
    if (!item.effectiveFrom && medication) item.effectiveFrom = medication.startDate;
    if (!item.doseAmountSnapshot && medication) item.doseAmountSnapshot = medication.doseAmount;
    if (!item.doseUnitSnapshot && medication) item.doseUnitSnapshot = medication.doseUnit;
    if (!item.updatedAt) item.updatedAt = medication?.updatedAt ?? new Date().toISOString();
  });
  value.medicationLogs.forEach(item => { if (!item.updatedAt) item.updatedAt = item.createdAt; });
  value.schemaVersion = 4;
  return value;
}

export function validateState(value: HealthState): void {
  if (value.schemaVersion !== 4 || value.members.length === 0 || !value.members.some(item => !item.archived)) throw new Error('家庭成员数据异常');
  unique(value.members.map(item => item.uuid), '家庭成员'); unique(value.conditions.map(item => item.uuid), '病情分类');
  unique(value.records.map(item => item.uuid), '病历'); unique(value.attachments.map(item => item.uuid), '附件');
  unique(value.followUps.map(item => item.uuid), '复查计划'); unique(value.occurrences.map(item => item.uuid), '复查记录');
  unique(value.medications.map(item => item.uuid), '药物'); unique(value.medicationSchedules.map(item => item.uuid), '用药计划');
  unique(value.medicationLogs.map(item => item.uuid), '用药记录');
  const members = new Set(value.members.map(item => item.uuid));
  const conditionMember = new Map(value.conditions.map(item => [item.uuid, item.memberUuid]));
  value.members.forEach(item => { if (!item.name.trim() || !item.relationship.trim() || item.nickname !== item.relationship) throw new Error('成员姓名或关系异常'); });
  value.conditions.forEach(item => { if (!members.has(item.memberUuid) || !item.name.trim()) throw new Error('病情分类成员引用异常'); });
  const recordIds = new Set(value.records.map(item => item.uuid)); const dayOrders = new Set<string>();
  value.records.forEach(item => {
    if (!members.has(item.memberUuid) || !item.title.trim() || !validDay(item.recordDate) || item.dayOrder < 0 || dayOrders.has(`${item.memberUuid}:${item.recordDate}:${item.dayOrder}`)) throw new Error('病历内容或顺序异常');
    dayOrders.add(`${item.memberUuid}:${item.recordDate}:${item.dayOrder}`);
    if (item.conditionUuid && conditionMember.get(item.conditionUuid) !== item.memberUuid) throw new Error('病历与病情分类不属于同一成员');
  });
  value.attachments.forEach(item => { if (!recordIds.has(item.recordUuid) || !item.relativePath || item.relativePath.includes('..') || item.relativePath.startsWith('/') || item.relativePath.includes('\\') || item.sizeBytes < 0 || item.sizeBytes > 104857600 || !/^[0-9a-f]{64}$/.test(item.sha256)) throw new Error('附件清单异常'); });
  const followIds = new Set(value.followUps.map(item => item.uuid)); const occurrenceKeys = new Set<string>();
  value.followUps.forEach(item => { if (!members.has(item.memberUuid) || !item.title.trim() || !validDay(item.anchorDate) || !validDay(item.nextDueDate) || item.leadDays < 0 || (item.conditionUuid && conditionMember.get(item.conditionUuid) !== item.memberUuid)) throw new Error('复查计划异常'); });
  value.occurrences.forEach(item => { const key = `${item.scheduleUuid}:${item.dueDate}`; if (!followIds.has(item.scheduleUuid) || occurrenceKeys.has(key)) throw new Error('复查记录异常或重复'); occurrenceKeys.add(key); });
  const medicationMember = new Map(value.medications.map(item => [item.uuid, item.memberUuid]));
  value.medications.forEach(item => { if (!members.has(item.memberUuid) || !item.name.trim() || !item.doseAmount.trim() || !item.doseUnit.trim() || !validDay(item.startDate) || (item.endDate && (!validDay(item.endDate) || item.endDate < item.startDate)) || (item.conditionUuid && conditionMember.get(item.conditionUuid) !== item.memberUuid)) throw new Error('药物内容异常'); });
  const scheduleMedication = new Map(value.medicationSchedules.map(item => [item.uuid, item.medicationUuid]));
  value.medicationSchedules.forEach(item => { if (!medicationMember.has(item.medicationUuid) || !validDay(item.effectiveFrom) || (item.effectiveTo && (!validDay(item.effectiveTo) || item.effectiveTo < item.effectiveFrom)) || !validTime(item.localTime) || !item.doseAmountSnapshot || !item.doseUnitSnapshot) throw new Error('用药计划异常'); });
  const logKeys = new Set<string>();
  value.medicationLogs.forEach(item => { const key = `${item.medicationUuid}:${item.scheduledAt}`; if (!medicationMember.has(item.medicationUuid) || (item.scheduleUuid && scheduleMedication.get(item.scheduleUuid) !== item.medicationUuid) || !['TAKEN', 'SKIPPED'].includes(item.status) || logKeys.has(key)) throw new Error('用药记录异常或重复'); logKeys.add(key); });
}

function unique(values: string[], label: string): void { if (new Set(values).size !== values.length) throw new Error(`${label} UUID 重复`); }
function validDay(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parts = value.split('-').map(item => Number(item));
  const date = new Date(parts[0], parts[1] - 1, parts[2]);
  return date.getFullYear() === parts[0] && date.getMonth() === parts[1] - 1 && date.getDate() === parts[2];
}
function validTime(value: string): boolean { return /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(value); }
