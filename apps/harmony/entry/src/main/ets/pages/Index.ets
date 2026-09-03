import { HealthStore } from '../store/HealthStore';
import { ClinicalRecord, FollowUpSchedule, Medication } from '../model/Models';
import { formatDate, nextOccurrence } from '../domain/Recurrence';
import { ReminderScheduler } from '../platform/ReminderScheduler';
import { util } from '@kit.ArkTS';

@Entry
@Component
struct Index {
  @State selected: number = 0;
  @State revision: number = 0;
  @State query: string = '';
  @State month: Date = new Date();

  aboutToAppear(): void { ReminderScheduler.requestPermission().then(() => ReminderScheduler.rebuild(HealthStore.instance.state)); }

  build() {
    Tabs({ barPosition: BarPosition.End, index: this.selected }) {
      TabContent() { this.calendarPage() }.tabBar('日历')
      TabContent() { this.followUpPage() }.tabBar('复查')
      TabContent() { this.medicationPage() }.tabBar('用药')
      TabContent() { this.settingsPage() }.tabBar('设置')
    }.onChange(index => this.selected = index)
  }

  @Builder calendarPage() {
    Column({ space: 12 }) {
      Text('病程日历').fontSize(28).fontWeight(FontWeight.Bold).width('100%')
      TextInput({ placeholder: '搜索病历、诊断、治疗或医院', text: this.query }).onChange(value => this.query = value)
      if (this.query.trim().length > 0) {
        List() {
          ForEach(this.searchResults(), (item: ClinicalRecord) => {
            ListItem() { Column() { Text(item.title).fontSize(18); Text(item.recordDate).fontSize(13).fontColor('#666666') }.alignItems(HorizontalAlign.Start).padding(12) }
          }, (item: ClinicalRecord) => item.uuid)
        }.layoutWeight(1)
      } else {
        Grid() {
          ForEach(this.monthDays(), (day: string) => {
            GridItem() {
              Column({ space: 3 }) {
                Text(day.substring(8)).fontSize(14).width('100%')
                ForEach(HealthStore.instance.state.records.filter(r => r.recordDate === day).slice(0, 2), (record: ClinicalRecord) => {
                  Text(record.title).fontSize(11).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%').padding(4).backgroundColor('#DDF2EA').borderRadius(6)
                }, (record: ClinicalRecord) => record.uuid)
                if (this.recordCount(day) > 2) Text(`+${this.recordCount(day) - 2}`).fontSize(10)
              }.height(82).alignItems(HorizontalAlign.Start)
            }
          })
        }.columnsTemplate('1fr 1fr 1fr 1fr 1fr 1fr 1fr').columnsGap(4).rowsGap(4).layoutWeight(1)
      }
      Button('新增病历').onClick(() => this.addExampleRecord())
    }.padding(16).width('100%').height('100%')
  }

  @Builder followUpPage() {
    Column({ space: 12 }) {
      Text('复查').fontSize(28).fontWeight(FontWeight.Bold).width('100%')
      List() { ForEach(HealthStore.instance.state.followUps.filter(item => item.enabled), (item: FollowUpSchedule) => { ListItem() { Column({ space: 6 }) { Text(item.title).fontSize(18); Text(`下次：${item.nextDueDate}`); Button('完成并计算下次').onClick(() => this.completeFollowUp(item)) }.alignItems(HorizontalAlign.Start).padding(12) } }, (item: FollowUpSchedule) => item.uuid) }.layoutWeight(1)
      Button('新增每三个月复查').onClick(() => this.addExampleFollowUp())
    }.padding(16).width('100%').height('100%')
  }

  @Builder medicationPage() {
    Column({ space: 12 }) {
      Text('用药').fontSize(28).fontWeight(FontWeight.Bold).width('100%')
      List() { ForEach(HealthStore.instance.state.medications.filter(item => !item.archived), (item: Medication) => { ListItem() { Column({ space: 6 }) { Text(item.name).fontSize(18); Text(`${item.doseAmount} ${item.doseUnit}`); Row({ space: 8 }) { Button('已服').onClick(() => this.markDose(item, 'TAKEN')); Button('跳过').onClick(() => this.markDose(item, 'SKIPPED')) } }.alignItems(HorizontalAlign.Start).padding(12) } }, (item: Medication) => item.uuid) }.layoutWeight(1)
      Button('新增示例用药').onClick(() => this.addExampleMedication())
    }.padding(16).width('100%').height('100%')
  }

  @Builder settingsPage() {
    Column({ space: 16 }) { Text('设置').fontSize(28).fontWeight(FontWeight.Bold); Text('全部资料仅保存在本机；应用不申请网络权限。数据库使用加密 RDB。'); Text('跨平台备份格式：.htbackup v2'); Button('重建提醒').onClick(() => ReminderScheduler.rebuild(HealthStore.instance.state)); Text('本应用仅用于记录和提醒，不提供诊断、处方或药物安全建议。').fontColor(Color.Red) }.alignItems(HorizontalAlign.Start).padding(16).width('100%').height('100%')
  }

  private searchResults(): ClinicalRecord[] {
    const needle = this.query.toLowerCase().trim();
    return HealthStore.instance.state.records.filter(item => [item.title, item.symptoms, item.diagnosis, item.treatment, item.medicationNotes, item.hospital, item.clinician, item.notes].some(value => value.toLowerCase().includes(needle)));
  }
  private monthDays(): string[] { const result: string[] = []; const year = this.month.getFullYear(); const month = this.month.getMonth(); const count = new Date(year, month + 1, 0).getDate(); for (let day = 1; day <= count; day++) result.push(formatDate(new Date(year, month, day))); return result; }
  private recordCount(day: string): number { return HealthStore.instance.state.records.filter(record => record.recordDate === day).length; }
  private async addExampleRecord(): Promise<void> { const now = new Date().toISOString(); await HealthStore.instance.update(state => state.records.push({ uuid: util.generateRandomUUID(true), recordDate: formatDate(new Date()), title: '新病历', stage: 'OTHER', symptoms: '', diagnosis: '', treatment: '', medicationNotes: '', hospital: '', clinician: '', notes: '', createdAt: now, updatedAt: now })); this.revision++; }
  private async addExampleFollowUp(): Promise<void> { const now = new Date().toISOString(); const today = formatDate(new Date()); await HealthStore.instance.update(state => state.followUps.push({ uuid: util.generateRandomUUID(true), title: '定期复查', recurrenceType: 'EVERY_N_MONTHS', interval: 3, anchorDate: today, anchorDayOfMonth: new Date().getDate(), reminderTime: '09:00', leadDays: 0, nextDueDate: today, enabled: true, createdAt: now, updatedAt: now })); this.revision++; await ReminderScheduler.rebuild(HealthStore.instance.state); }
  private async completeFollowUp(item: FollowUpSchedule): Promise<void> { await HealthStore.instance.update(state => { const target = state.followUps.find(value => value.uuid === item.uuid); if (!target) return; const next = nextOccurrence(target.recurrenceType, target.nextDueDate, target.interval, target.anchorDayOfMonth); if (next) target.nextDueDate = next; else target.enabled = false; target.updatedAt = new Date().toISOString(); }); this.revision++; await ReminderScheduler.rebuild(HealthStore.instance.state); }
  private async addExampleMedication(): Promise<void> { const now = new Date().toISOString(); await HealthStore.instance.update(state => state.medications.push({ uuid: util.generateRandomUUID(true), name: '新药物', doseAmount: '1', doseUnit: '片', instructions: '', startDate: formatDate(new Date()), mode: 'SCHEDULED', archived: false, createdAt: now, updatedAt: now })); this.revision++; }
  private async markDose(item: Medication, status: string): Promise<void> { const now = new Date().toISOString(); await HealthStore.instance.update(state => state.medicationLogs.push({ uuid: util.generateRandomUUID(true), medicationUuid: item.uuid, scheduledAt: now.substring(0, 16), actualAt: status === 'TAKEN' ? now.substring(0, 16) : undefined, status: status, doseAmountSnapshot: item.doseAmount, doseUnitSnapshot: item.doseUnit, createdAt: now, updatedAt: now })); this.revision++; }
}
