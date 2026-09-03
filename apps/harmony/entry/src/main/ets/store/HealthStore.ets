import { common } from '@kit.AbilityKit';
import { relationalStore } from '@kit.ArkData';
import { HealthState, emptyState } from '../model/Models';

const DATABASE_NAME = 'health_timeline.db';
const CREATE_STATE = 'CREATE TABLE IF NOT EXISTS app_state(id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL, updated_at TEXT NOT NULL)';

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
    const check = await database.querySql('PRAGMA quick_check');
    try {
      if (!check.goToFirstRow() || check.getString(0).toLowerCase() !== 'ok') throw new Error('数据库完整性检查失败');
    } finally { check.close(); }
    const rows = await database.querySql('SELECT payload FROM app_state WHERE id=1');
    try {
      if (rows.goToFirstRow()) this.value = JSON.parse(rows.getString(0)) as HealthState;
    } finally { rows.close(); }
    this.database = database;
    this.initialized = true;
  }

  async update(change: (candidate: HealthState) => void): Promise<void> {
    if (!this.database) throw new Error('数据库尚未初始化');
    const candidate = JSON.parse(JSON.stringify(this.value)) as HealthState;
    change(candidate);
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
}
