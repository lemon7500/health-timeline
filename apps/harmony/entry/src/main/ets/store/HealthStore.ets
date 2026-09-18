import { common } from '@kit.AbilityKit';
import { relationalStore } from '@kit.ArkData';
import { ClinicalRecord, FamilyMember, FollowUpSchedule, HealthState, Medication, MedicationLog, MedicationSchedule, emptyState, normalizeState, validateState } from '../model/Models';

const DATABASE_NAME = 'health_timeline.db';
const CREATE_STATE = 'CREATE TABLE IF NOT EXISTS app_state(id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL, updated_at TEXT NOT NULL)';
const CREATE_MIGRATION_BACKUP = 'CREATE TABLE IF NOT EXISTS migration_backup(id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL, created_at TEXT NOT NULL)';

export class HealthStore {
  static readonly instance = new HealthStore();
  private database?: relationalStore.RdbStore;
  private value: HealthState = emptyState();
  private initialized = false;

  get state(): HealthState { return this.value; }

  async initialize(context: common.UIAbilityContext): Promise<void> {
    if (this.initialized) return;
    const config: relationalStore.StoreConfig = {
      name: DATABASE_NAME,
      securityLevel: relationalStore.SecurityLevel.S3,
      encrypt: true
    };
    const database = await relationalStore.getRdbStore(context, config);
    await database.executeSql(CREATE_STATE);
    await database.executeSql(CREATE_MIGRATION_BACKUP);
    const check = await database.querySql('PRAGMA quick_check');
    try {
      if (!check.goToFirstRow() || check.getString(0).toLowerCase() !== 'ok') throw new Error('数据库完整性检查失败');
    } finally { check.close(); }
    const rows = await database.querySql('SELECT payload FROM app_state WHERE id=1');
    let sourcePayload: string = '';
    try {
      if (rows.goToFirstRow()) { sourcePayload = rows.getString(0); this.value = normalizeState(JSON.parse(sourcePayload) as HealthState); }
      else this.value = emptyState();
    } finally { rows.close(); }
    validateState(this.value);
    this.database = database;
    await this.persistNormalizedState(sourcePayload);
    this.initialized = true;
  }

  async update(change: (candidate: HealthState) => void): Promise<void> {
    if (!this.database) throw new Error('数据库尚未初始化');
    const candidate = JSON.parse(JSON.stringify(this.value)) as HealthState;
    change(candidate);
    normalizeState(candidate);
    validateState(candidate);
    const payload = JSON.stringify(candidate);
    this.database.beginTransaction();
    try {
      await this.database.executeSql('INSERT OR REPLACE INTO app_state(id,payload,updated_at) VALUES(1,?,?)', [payload, new Date().toISOString()]);
      this.database.commit();
      this.value = candidate;
    } catch (error) {
      this.database.rollBack();
      throw error;
    }
  }

  currentMember(): FamilyMember {
    return this.value.members.find(item => item.uuid === this.value.selectedMemberUuid && !item.archived) ?? this.value.members.find(item => !item.archived) ?? this.value.members[0];
  }

  recordsForCurrentMember(): ClinicalRecord[] { return this.value.records.filter(item => item.memberUuid === this.currentMember().uuid); }
  followUpsForCurrentMember(): FollowUpSchedule[] { return this.value.followUps.filter(item => item.memberUuid === this.currentMember().uuid); }
  medicationsForCurrentMember(): Medication[] { return this.value.medications.filter(item => item.memberUuid === this.currentMember().uuid); }

  schedulesForDay(medication: Medication, day: string): MedicationSchedule[] {
    return this.value.medicationSchedules.filter(item => item.medicationUuid === medication.uuid && item.enabled && item.effectiveFrom <= day && (!item.effectiveTo || item.effectiveTo >= day));
  }

  logsForDay(medication: Medication, day: string): MedicationLog[] {
    return this.value.medicationLogs.filter(item => item.medicationUuid === medication.uuid && item.scheduledAt.substring(0, 10) === day);
  }

  private async persistNormalizedState(sourcePayload: string): Promise<void> {
    if (!this.database) return;
    const payload = JSON.stringify(this.value); const now = new Date().toISOString();
    if (payload === sourcePayload) return;
    this.database.beginTransaction();
    try {
      if (sourcePayload.length > 0) await this.database.executeSql('INSERT OR REPLACE INTO migration_backup(id,payload,created_at) VALUES(1,?,?)', [sourcePayload, now]);
      await this.database.executeSql('INSERT OR REPLACE INTO app_state(id,payload,updated_at) VALUES(1,?,?)', [payload, now]);
      this.database.commit();
    } catch (error) { this.database.rollBack(); throw error; }
  }
}
