import { promptAction } from '@kit.ArkUI';
import { util } from '@kit.ArkTS';
import { formatDate, nextOccurrence } from '../domain/Recurrence';
import { ClinicalRecord, FollowUpSchedule, Medication, MedicationSchedule, newMember } from '../model/Models';
import { ReminderScheduler } from '../platform/ReminderScheduler';
import { HealthStore } from '../store/HealthStore';

@Entry
@Component
struct Index {
  @State selected: number = 0;
  @State revision: number = 0;
  @State query: string = '';
  @State month: Date = new Date();
  @State selectedMedicationDay: string = formatDate(new Date());
  @State showRecordForm: boolean = false;
  @State showFollowUpForm: boolean = false;
  @State showMedicationForm: boolean = false;
  @State editingMedicationUuid: string = '';
  @State showMemberForm: boolean = false;
  @State recordMemberUuid: string = '';
  @State editingRecordUuid: string = '';
  @State recordDate: string = formatDate(new Date());
  @State recordTitle: string = '';
  @State recordCondition: string = '';
  @State recordSymptoms: string = '';
  @State recordDiagnosis: string = '';
  @State recordTreatment: string = '';
  @State recordMedication: string = '';
  @State recordHospital: string = '';
  @State recordClinician: string = '';
  @State recordNotes: string = '';
  @State followTitle: string = '';
  @State followDate: string = formatDate(new Date());
  @State followMonths: number = 3;
  @State medicationName: string = '';
  @State medicationAmount: string = '1';
  @State medicationUnit: string = '片';
  @State medicationHour: number = 8;
  @State medicationMinute: number = 0;
  @State doseHour: number = new Date().getHours();
  @State doseMinute: number = Math.floor(new Date().getMinutes() / 5) * 5;
  @State memberName: string = '';
  @State memberRelationship: string = '';

  aboutToAppear(): void {
    ReminderScheduler.rebuild(HealthStore.instance.state).catch(() => {});
  }

  build() {
    Tabs({ barPosition: BarPosition.End, index: this.selected }) {
      TabContent() { this.calendarPage() }.tabBar('日历')
      TabContent() { this.followUpPage() }.tabBar('复查')
      TabContent() { this.medicationPage() }.tabBar('用药')
      TabContent() { this.settingsPage() }.tabBar('设置')
    }.onChange(index => this.selected = index)
  }

  @Builder memberSelector() {
    Row({ space: 8 }) {
      Text('当前成员').fontSize(13).fontColor('#666666')
      Select(HealthStore.instance.state.members.filter(item => !item.archived).map(item => ({ value: item.relationship })))
        .selected(Math.max(0, HealthStore.instance.state.members.filter(item => !item.archived).findIndex(item => item.uuid === HealthStore.instance.state.selectedMemberUuid)))
        .onSelect(async (index: number) => {
          const members = HealthStore.instance.state.members.filter(item => !item.archived);
          try {
            if (index < members.length) await HealthStore.instance.update(state => state.selectedMemberUuid = members[index].uuid);
          } catch (_) { promptAction.showToast({ message: '成员切换失败，原有资料没有改变' }); return; }
          this.revision++;
        })
    }.width('100%')
  }

  @Builder calendarPage() {
    Column({ space: 10 }) {
      Text('病程日历').fontSize(28).fontWeight(FontWeight.Bold).width('100%')
      this.memberSelector()
      Row() {
        Button('‹').onClick(() => this.changeMonth(-1)); Blank(); Text(`${this.month.getFullYear()} 年 ${this.month.getMonth() + 1} 月`).fontSize(20); Blank(); Button('›').onClick(() => this.changeMonth(1))
      }.width('100%')
      TextInput({ placeholder: '搜索标题、病名、诊断、治疗、医生或备注', text: this.query }).onChange(value => this.query = value)
      if (this.query.trim().length > 0) {
        List() {
          ForEach(this.searchResults(), (item: ClinicalRecord) => {
            ListItem() { Column() { Text(item.title).fontSize(18); Text(item.recordDate).fontSize(13).fontColor('#666666'); Text(item.diagnosis).maxLines(2) }.alignItems(HorizontalAlign.Start).padding(12).onClick(() => this.openRecord(item)) }
          }, (item: ClinicalRecord) => item.uuid)
        }.layoutWeight(1)
      } else {
        Grid() {
          ForEach(this.monthDays(), (day: string) => {
            GridItem() {
              Column({ space: 3 }) {
                Text(day.substring(8)).fontSize(14).width('100%')
                ForEach(this.recordsForDay(day).slice(0, 2), (record: ClinicalRecord) => {
                  Text(record.title).fontSize(11).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%').padding(4).backgroundColor('#DDF2EA').borderRadius(6).onClick(() => this.openRecord(record))
                }, (record: ClinicalRecord) => record.uuid)
                if (this.recordsForDay(day).length > 2) Text(`+${this.recordsForDay(day).length - 2}`).fontSize(10)
              }.height(82).alignItems(HorizontalAlign.Start)
            }
          })
        }.columnsTemplate('1fr 1fr 1fr 1fr 1fr 1fr 1fr').columnsGap(4).rowsGap(4).layoutWeight(1)
      }
      Button(this.showRecordForm ? '收起病历表单' : '新增病历').onClick(() => this.showRecordForm ? this.closeRecordForm() : this.beginNewRecord())
      if (this.showRecordForm) this.recordForm()
    }.padding(16).width('100%').height('100%')
  }

  @Builder recordForm() {
    Scroll() {
      Column({ space: 6 }) {
        TextInput({ placeholder: '日期 yyyy-MM-dd', text: this.recordDate }).onChange(value => this.recordDate = value)
        TextInput({ placeholder: '标题（病名在前）', text: this.recordTitle }).onChange(value => this.recordTitle = value)
        TextInput({ placeholder: '病情分类', text: this.recordCondition }).onChange(value => this.recordCondition = value)
        TextArea({ placeholder: '症状/病情', text: this.recordSymptoms }).onChange(value => this.recordSymptoms = value)
        TextArea({ placeholder: '诊断', text: this.recordDiagnosis }).onChange(value => this.recordDiagnosis = value)
        TextArea({ placeholder: '治疗方案', text: this.recordTreatment }).onChange(value => this.recordTreatment = value)
        TextArea({ placeholder: '就诊用药记录', text: this.recordMedication }).onChange(value => this.recordMedication = value)
        TextInput({ placeholder: '医院', text: this.recordHospital }).onChange(value => this.recordHospital = value)
        TextInput({ placeholder: '医生', text: this.recordClinician }).onChange(value => this.recordClinician = value)
        TextArea({ placeholder: '其他备注', text: this.recordNotes }).onChange(value => this.recordNotes = value)
        if (this.recordMemberUuid !== HealthStore.instance.state.selectedMemberUuid) Text('此表单仍保存到打开时选择的成员，避免资料串人。').fontSize(12).fontColor('#A06000')
        Button(this.editingRecordUuid.length > 0 ? '核对后保存修改' : '核对后保存').onClick(() => this.saveRecord())
      }
    }.height(330)
  }

  @Builder followUpPage() {
    Column({ space: 12 }) {
      Text('复查').fontSize(28).fontWeight(FontWeight.Bold).width('100%'); this.memberSelector()
      List() {
        ForEach(HealthStore.instance.followUpsForCurrentMember().filter(item => item.enabled), (item: FollowUpSchedule) => {
          ListItem() { Column({ space: 6 }) { Text(item.title).fontSize(18); Text(`下次：${item.nextDueDate} · 提前 ${item.leadDays} 天`); Button('完成并计算下次').onClick(() => this.completeFollowUp(item)) }.alignItems(HorizontalAlign.Start).padding(12) }
        }, (item: FollowUpSchedule) => item.uuid)
      }.layoutWeight(1)
      Button(this.showFollowUpForm ? '取消新增' : '新增复查').onClick(() => this.showFollowUpForm = !this.showFollowUpForm)
      if (this.showFollowUpForm) {
        Column({ space: 6 }) {
          TextInput({ placeholder: '复查标题', text: this.followTitle }).onChange(value => this.followTitle = value)
          TextInput({ placeholder: '首次日期 yyyy-MM-dd', text: this.followDate }).onChange(value => this.followDate = value)
          Text(`每 ${this.followMonths} 个月`); Slider({ value: this.followMonths, min: 1, max: 12, step: 1 }).onChange(value => this.followMonths = Math.round(value))
          Text('默认提前 3 天、09:00 提醒').fontSize(13).fontColor('#666666')
          Button('保存复查').onClick(() => this.saveFollowUp())
        }
      }
    }.padding(16).width('100%').height('100%')
  }

  @Builder medicationPage() {
    Column({ space: 10 }) {
      Text('用药日历').fontSize(28).fontWeight(FontWeight.Bold).width('100%'); this.memberSelector()
      Row() {
        Button('‹').onClick(() => this.changeMonth(-1)); Blank(); Text(`${this.month.getFullYear()} 年 ${this.month.getMonth() + 1} 月`).fontSize(18); Blank(); Button('›').onClick(() => this.changeMonth(1))
      }.width('100%')
      Text(`查看日期：${this.selectedMedicationDay}`).width('100%')
      Grid() {
        ForEach(this.monthDays(), (day: string) => {
          GridItem() { Button(day.substring(8)).onClick(() => this.selectedMedicationDay = day).backgroundColor(day === this.selectedMedicationDay ? '#D8E8FF' : '#FFFFFF') }
        })
      }.columnsTemplate('1fr 1fr 1fr 1fr 1fr 1fr 1fr').height(190)
      if (this.selectedMedicationDay <= formatDate(new Date())) {
        Text(`实际服用时间 ${this.twoDigits(this.doseHour)}:${this.twoDigits(this.doseMinute)}（每 5 分钟）`).width('100%')
        Slider({ value: this.doseHour, min: 0, max: 23, step: 1 }).onChange(value => this.doseHour = Math.round(value))
        Slider({ value: this.doseMinute, min: 0, max: 55, step: 5 }).onChange(value => this.doseMinute = Math.round(value / 5) * 5)
      } else {
        Text('未来日期仅供查看，不能提前打卡。').fontSize(13).fontColor('#A06000').width('100%')
      }
      List() {
        ForEach(HealthStore.instance.medicationsForCurrentMember().filter(item => !item.archived && item.startDate <= this.selectedMedicationDay && (!item.endDate || item.endDate >= this.selectedMedicationDay)), (item: Medication) => {
          ListItem() {
            Column({ space: 6 }) {
              Text(`${item.name} ${item.doseAmount}${item.doseUnit}`).fontSize(18)
              ForEach(HealthStore.instance.schedulesForDay(item, this.selectedMedicationDay), (schedule: MedicationSchedule) => {
                Row({ space: 8 }) {
                  Text(`${schedule.localTime} · ${this.logStatus(item, schedule)}`).layoutWeight(1)
                  Button('已服').enabled(this.selectedMedicationDay <= formatDate(new Date())).onClick(() => this.markDose(item, schedule, 'TAKEN'))
                  Button('跳过').enabled(this.selectedMedicationDay <= formatDate(new Date())).onClick(() => this.markDose(item, schedule, 'SKIPPED'))
                }
              }, (schedule: MedicationSchedule) => schedule.uuid)
              Row({ space: 8 }) {
                if (item.startDate <= formatDate(new Date()) && (!item.endDate || item.endDate >= formatDate(new Date()))) {
                  Button('修改计划').onClick(() => this.beginMedicationEdit(item))
                  Button('今日结束疗程').onClick(() => this.endMedication(item))
                }
              }
            }.alignItems(HorizontalAlign.Start).padding(8)
          }
        }, (item: Medication) => item.uuid)
      }.layoutWeight(1)
      Button(this.showMedicationForm ? '取消用药表单' : '新增用药').onClick(() => this.showMedicationForm ? this.closeMedicationForm() : this.beginNewMedication())
      if (this.showMedicationForm) this.medicationForm()
    }.padding(16).width('100%').height('100%')
  }

  @Builder medicationForm() {
    Column({ space: 6 }) {
      TextInput({ placeholder: '药名', text: this.medicationName }).onChange(value => this.medicationName = value)
      Row({ space: 6 }) {
        TextInput({ placeholder: '剂量', text: this.medicationAmount }).onChange(value => this.medicationAmount = value)
        TextInput({ placeholder: '单位', text: this.medicationUnit }).onChange(value => this.medicationUnit = value)
      }
      Text(`时间 ${this.twoDigits(this.medicationHour)}:${this.twoDigits(this.medicationMinute)}`)
      Slider({ value: this.medicationHour, min: 0, max: 23, step: 1 }).onChange(value => this.medicationHour = Math.round(value))
      Slider({ value: this.medicationMinute, min: 0, max: 55, step: 5 }).onChange(value => this.medicationMinute = Math.round(value / 5) * 5)
      if (this.editingMedicationUuid.length > 0) Text('修改默认从明天生效，过去的时间和剂量保持不变。').fontSize(12).fontColor('#666666')
      Button(this.editingMedicationUuid.length > 0 ? '保存修改（明日起生效）' : '保存用药').onClick(() => this.saveMedication())
    }
  }

  @Builder settingsPage() {
    Column({ space: 12 }) {
      Text('设置').fontSize(28).fontWeight(FontWeight.Bold).width('100%')
      Text('家庭成员').fontSize(20).width('100%')
      ForEach(HealthStore.instance.state.members, item => { Row() { Column() { Text(item.relationship); Text(item.name).fontSize(13).fontColor('#666666') }.alignItems(HorizontalAlign.Start); Blank(); Text(item.archived ? '已归档' : '正常') }.width('100%') }, item => item.uuid)
      Button(this.showMemberForm ? '取消新增成员' : '新增成员').onClick(() => this.showMemberForm = !this.showMemberForm)
      if (this.showMemberForm) {
        TextInput({ placeholder: '姓名', text: this.memberName }).onChange(value => this.memberName = value)
        TextInput({ placeholder: '关系（例如：母亲）', text: this.memberRelationship }).onChange(value => this.memberRelationship = value)
        Button('保存成员').onClick(() => this.saveMember())
      }
      Divider(); Text('全部资料仅保存在本机；应用不申请网络权限。数据库使用加密 RDB。').width('100%')
      Text('跨平台备份清单：.htbackup v4；正式导入前必须完整验证密码、引用及附件 SHA-256。').width('100%')
      Button('申请通知权限并重建提醒').onClick(async () => {
        const allowed = await ReminderScheduler.requestPermission();
        if (!allowed) { promptAction.showToast({ message: '通知权限未开启，请到系统设置允许' }); return; }
        try {
          const result = await ReminderScheduler.rebuild(HealthStore.instance.state);
          promptAction.showToast({ message: result.failed > 0 ? `已安排 ${result.scheduled} 项，${result.failed} 项失败` : `已安排 ${result.scheduled} 项提醒` });
        } catch (_) { promptAction.showToast({ message: '提醒重建失败，请检查系统权限' }); }
      })
      Text('本应用仅用于记录和提醒，不提供诊断、处方或药物安全建议。').fontColor(Color.Red).width('100%')
    }.alignItems(HorizontalAlign.Start).padding(16).width('100%').height('100%')
  }

  private recordsForDay(day: string): ClinicalRecord[] { return HealthStore.instance.recordsForCurrentMember().filter(record => record.recordDate === day).sort((a, b) => a.dayOrder - b.dayOrder); }
  private searchResults(): ClinicalRecord[] {
    const needle = this.query.toLowerCase().trim();
    const conditions = new Map(HealthStore.instance.state.conditions.map(item => [item.uuid, item.name]));
    return HealthStore.instance.recordsForCurrentMember().filter(item => [item.title, conditions.get(item.conditionUuid ?? '') ?? '', item.symptoms, item.diagnosis, item.treatment, item.medicationNotes, item.hospital, item.clinician, item.notes].some(value => value.toLowerCase().includes(needle)));
  }
  private monthDays(): string[] { const result: string[] = []; const year = this.month.getFullYear(); const month = this.month.getMonth(); const count = new Date(year, month + 1, 0).getDate(); for (let day = 1; day <= count; day++) result.push(formatDate(new Date(year, month, day))); return result; }
  private changeMonth(delta: number): void { this.month = new Date(this.month.getFullYear(), this.month.getMonth() + delta, 1); this.selectedMedicationDay = formatDate(this.month); }

  private beginNewRecord(): void {
    this.editingRecordUuid = '';
    this.recordMemberUuid = HealthStore.instance.currentMember().uuid;
    this.recordDate = formatDate(new Date()); this.recordTitle = ''; this.recordCondition = '';
    this.recordSymptoms = ''; this.recordDiagnosis = ''; this.recordTreatment = ''; this.recordMedication = '';
    this.recordHospital = ''; this.recordClinician = ''; this.recordNotes = ''; this.showRecordForm = true;
  }

  private openRecord(record: ClinicalRecord): void {
    this.editingRecordUuid = record.uuid; this.recordMemberUuid = record.memberUuid;
    this.recordDate = record.recordDate; this.recordTitle = record.title;
    this.recordCondition = HealthStore.instance.state.conditions.find(item => item.uuid === record.conditionUuid)?.name ?? '';
    this.recordSymptoms = record.symptoms; this.recordDiagnosis = record.diagnosis; this.recordTreatment = record.treatment;
    this.recordMedication = record.medicationNotes; this.recordHospital = record.hospital; this.recordClinician = record.clinician;
    this.recordNotes = record.notes; this.showRecordForm = true;
  }

  private closeRecordForm(): void { this.showRecordForm = false; this.editingRecordUuid = ''; this.recordMemberUuid = ''; }

  private async saveRecord(): Promise<void> {
    if (!this.validInputDate(this.recordDate) || this.recordTitle.trim().length === 0) { promptAction.showToast({ message: '请填写有效日期和标题' }); return; }
    const member = HealthStore.instance.state.members.find(item => item.uuid === this.recordMemberUuid && !item.archived);
    if (!member) { promptAction.showToast({ message: '表单所属成员已归档，请取消后重新填写' }); return; }
    const now = new Date().toISOString();
    try { await HealthStore.instance.update(state => {
      let conditionUuid: string | undefined = undefined;
      if (this.recordCondition.trim().length > 0) {
        const existing = state.conditions.find(item => item.memberUuid === member.uuid && item.name === this.recordCondition.trim());
        conditionUuid = existing?.uuid;
        if (!conditionUuid) { conditionUuid = util.generateRandomUUID(true); state.conditions.push({ uuid: conditionUuid, memberUuid: member.uuid, name: this.recordCondition.trim(), color: 4281293654, notes: '', archived: false, createdAt: now, updatedAt: now }); }
      }
      const existing = state.records.find(item => item.uuid === this.editingRecordUuid);
      if (existing) {
        if (existing.memberUuid !== member.uuid) throw new Error('不能更改病历所属成员');
        if (existing.recordDate !== this.recordDate) {
          existing.dayOrder = state.records.filter(item => item.uuid !== existing.uuid && item.memberUuid === member.uuid && item.recordDate === this.recordDate).reduce((maximum, item) => Math.max(maximum, item.dayOrder + 1), 0);
        }
        existing.conditionUuid = conditionUuid; existing.recordDate = this.recordDate; existing.title = this.recordTitle.trim();
        existing.symptoms = this.recordSymptoms; existing.diagnosis = this.recordDiagnosis; existing.treatment = this.recordTreatment;
        existing.medicationNotes = this.recordMedication; existing.hospital = this.recordHospital; existing.clinician = this.recordClinician;
        existing.notes = this.recordNotes; existing.updatedAt = now;
      } else {
        const order = state.records.filter(item => item.memberUuid === member.uuid && item.recordDate === this.recordDate).reduce((maximum, item) => Math.max(maximum, item.dayOrder + 1), 0);
        state.records.push({ uuid: util.generateRandomUUID(true), memberUuid: member.uuid, conditionUuid: conditionUuid, recordDate: this.recordDate, dayOrder: order, title: this.recordTitle.trim(), stage: 'OTHER', symptoms: this.recordSymptoms, diagnosis: this.recordDiagnosis, treatment: this.recordTreatment, medicationNotes: this.recordMedication, hospital: this.recordHospital, clinician: this.recordClinician, notes: this.recordNotes, createdAt: now, updatedAt: now });
      }
    }); } catch (_) { promptAction.showToast({ message: '病历未保存，原有资料没有改变' }); return; }
    this.closeRecordForm(); this.revision++;
  }

  private async saveFollowUp(): Promise<void> {
    if (!this.validInputDate(this.followDate) || this.followTitle.trim().length === 0) { promptAction.showToast({ message: '请填写有效日期和标题' }); return; }
    const member = HealthStore.instance.currentMember(); const now = new Date().toISOString();
    try { await HealthStore.instance.update(state => state.followUps.push({ uuid: util.generateRandomUUID(true), memberUuid: member.uuid, title: this.followTitle.trim(), recurrenceType: 'EVERY_N_MONTHS', interval: this.followMonths, anchorDate: this.followDate, anchorDayOfMonth: Number(this.followDate.substring(8)), reminderTime: '09:00', leadDays: 3, nextDueDate: this.followDate, enabled: true, createdAt: now, updatedAt: now })); }
    catch (_) { promptAction.showToast({ message: '复查计划未保存，原有资料没有改变' }); return; }
    this.followTitle = ''; this.showFollowUpForm = false; this.revision++; await ReminderScheduler.rebuild(HealthStore.instance.state);
  }

  private async completeFollowUp(item: FollowUpSchedule): Promise<void> {
    try { await HealthStore.instance.update(state => {
      if (state.occurrences.some(value => value.scheduleUuid === item.uuid && value.dueDate === item.nextDueDate)) return;
      const now = new Date().toISOString(); state.occurrences.push({ uuid: util.generateRandomUUID(true), scheduleUuid: item.uuid, dueDate: item.nextDueDate, status: 'DONE', completedAt: now, createdAt: now, updatedAt: now });
      const target = state.followUps.find(value => value.uuid === item.uuid); if (!target) return;
      const next = nextOccurrence(target.recurrenceType, target.nextDueDate, target.interval, target.anchorDayOfMonth); if (next) target.nextDueDate = next; else target.enabled = false; target.updatedAt = now;
    }); } catch (_) { promptAction.showToast({ message: '复查状态未修改，请稍后重试' }); return; }
    this.revision++; await ReminderScheduler.rebuild(HealthStore.instance.state);
  }

  private async saveMedication(): Promise<void> {
    if (this.medicationName.trim().length === 0 || this.medicationAmount.trim().length === 0 || this.medicationUnit.trim().length === 0) { promptAction.showToast({ message: '请填写药名、剂量和单位' }); return; }
    const member = HealthStore.instance.currentMember(); const now = new Date().toISOString(); const medicationUuid = this.editingMedicationUuid.length > 0 ? this.editingMedicationUuid : util.generateRandomUUID(true); const time = `${this.twoDigits(this.medicationHour)}:${this.twoDigits(this.medicationMinute)}`;
    try { await HealthStore.instance.update(state => {
      const existing = state.medications.find(item => item.uuid === this.editingMedicationUuid);
      if (existing) {
        if (existing.memberUuid !== member.uuid) throw new Error('不能更改药物所属成员');
        const today = formatDate(new Date()); const tomorrowDate = new Date(); tomorrowDate.setDate(tomorrowDate.getDate() + 1); const tomorrow = formatDate(tomorrowDate);
        state.medicationSchedules = state.medicationSchedules.filter(schedule => schedule.medicationUuid !== existing.uuid || schedule.effectiveFrom < tomorrow);
        state.medicationSchedules.filter(schedule => schedule.medicationUuid === existing.uuid && (!schedule.effectiveTo || schedule.effectiveTo >= tomorrow)).forEach(schedule => { schedule.effectiveTo = today; schedule.updatedAt = now; });
        existing.name = this.medicationName.trim(); existing.doseAmount = this.medicationAmount.trim(); existing.doseUnit = this.medicationUnit.trim(); existing.updatedAt = now;
        state.medicationSchedules.push({ uuid: util.generateRandomUUID(true), medicationUuid: existing.uuid, localTime: time, enabled: true, effectiveFrom: tomorrow, doseAmountSnapshot: existing.doseAmount, doseUnitSnapshot: existing.doseUnit, updatedAt: now });
      } else {
        state.medications.push({ uuid: medicationUuid, memberUuid: member.uuid, name: this.medicationName.trim(), doseAmount: this.medicationAmount.trim(), doseUnit: this.medicationUnit.trim(), instructions: '', startDate: formatDate(new Date()), mode: 'SCHEDULED', archived: false, createdAt: now, updatedAt: now });
        state.medicationSchedules.push({ uuid: util.generateRandomUUID(true), medicationUuid: medicationUuid, localTime: time, enabled: true, effectiveFrom: formatDate(new Date()), doseAmountSnapshot: this.medicationAmount.trim(), doseUnitSnapshot: this.medicationUnit.trim(), updatedAt: now });
      }
    }); } catch (_) { promptAction.showToast({ message: '用药计划未保存，原有资料没有改变' }); return; }
    this.closeMedicationForm(); this.revision++; await ReminderScheduler.rebuild(HealthStore.instance.state);
  }

  private beginNewMedication(): void {
    this.editingMedicationUuid = ''; this.medicationName = ''; this.medicationAmount = '1'; this.medicationUnit = '片';
    this.medicationHour = 8; this.medicationMinute = 0; this.showMedicationForm = true;
  }

  private beginMedicationEdit(medication: Medication): void {
    this.editingMedicationUuid = medication.uuid; this.medicationName = medication.name;
    this.medicationAmount = medication.doseAmount; this.medicationUnit = medication.doseUnit;
    const tomorrowDate = new Date(); tomorrowDate.setDate(tomorrowDate.getDate() + 1); const tomorrow = formatDate(tomorrowDate);
    const schedule = HealthStore.instance.schedulesForDay(medication, tomorrow)[0] ?? HealthStore.instance.state.medicationSchedules.find(item => item.medicationUuid === medication.uuid);
    const parts = schedule?.localTime.split(':').map(value => Number(value)) ?? [8, 0];
    this.medicationHour = parts[0]; this.medicationMinute = parts[1]; this.showMedicationForm = true;
  }

  private closeMedicationForm(): void { this.showMedicationForm = false; this.editingMedicationUuid = ''; this.medicationName = ''; }

  private async endMedication(medication: Medication): Promise<void> {
    try { await HealthStore.instance.update(state => {
      const target = state.medications.find(item => item.uuid === medication.uuid); if (!target) return;
      const today = formatDate(new Date()); const endedAt = new Date().toISOString();
      target.archivedPreviousEndDate = target.endDate; target.endDate = today;
      target.endedAt = endedAt; target.archived = true; target.updatedAt = endedAt;
      state.medicationSchedules.filter(item => item.medicationUuid === target.uuid && item.enabled).forEach(item => {
        item.enabled = false; item.pausedByCourseEnd = true; item.updatedAt = endedAt;
      });
    }); } catch (_) { promptAction.showToast({ message: '疗程结束日期未保存' }); return; }
    this.revision++; await ReminderScheduler.rebuild(HealthStore.instance.state);
  }

  private logStatus(medication: Medication, schedule: MedicationSchedule): string {
    const log = HealthStore.instance.logsForDay(medication, this.selectedMedicationDay).find(item => item.scheduleUuid === schedule.uuid);
    return !log ? '未记录' : log.status === 'TAKEN' ? `已服 ${log.actualAt?.substring(11, 16) ?? ''}` : '已跳过';
  }

  private async markDose(medication: Medication, schedule: MedicationSchedule, status: string): Promise<void> {
    if (this.selectedMedicationDay > formatDate(new Date())) { promptAction.showToast({ message: '未来日期不能提前打卡' }); return; }
    const scheduledAt = `${this.selectedMedicationDay}T${schedule.localTime}:00`;
    try { await HealthStore.instance.update(state => {
      const existing = state.medicationLogs.find(item => item.medicationUuid === medication.uuid && item.scheduleUuid === schedule.uuid && item.scheduledAt === scheduledAt);
      const now = new Date(); const actual = `${this.selectedMedicationDay}T${this.twoDigits(this.doseHour)}:${this.twoDigits(this.doseMinute)}:00`;
      if (existing) { existing.status = status; existing.actualAt = status === 'TAKEN' ? actual : undefined; existing.updatedAt = now.toISOString(); return; }
      state.medicationLogs.push({ uuid: util.generateRandomUUID(true), medicationUuid: medication.uuid, scheduleUuid: schedule.uuid, scheduledAt: scheduledAt, actualAt: status === 'TAKEN' ? actual : undefined, status: status, doseAmountSnapshot: schedule.doseAmountSnapshot, doseUnitSnapshot: schedule.doseUnitSnapshot, createdAt: now.toISOString(), updatedAt: now.toISOString() });
    }); } catch (_) { promptAction.showToast({ message: '用药记录未保存，请稍后重试' }); return; }
    this.revision++;
  }

  private async saveMember(): Promise<void> {
    if (this.memberName.trim().length === 0 || this.memberRelationship.trim().length === 0) { promptAction.showToast({ message: '请填写姓名和关系' }); return; }
    const member = newMember(this.memberName.trim(), this.memberRelationship.trim());
    try { await HealthStore.instance.update(state => { state.members.push(member); state.selectedMemberUuid = member.uuid; }); }
    catch (_) { promptAction.showToast({ message: '成员未保存，原有资料没有改变' }); return; }
    this.memberName = ''; this.memberRelationship = ''; this.showMemberForm = false; this.revision++;
  }

  private twoDigits(value: number): string { return `${value}`.padStart(2, '0'); }
  private validInputDate(value: string): boolean {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    const parts = value.split('-').map(item => Number(item));
    const date = new Date(parts[0], parts[1] - 1, parts[2]);
    return date.getFullYear() === parts[0] && date.getMonth() === parts[1] - 1 && date.getDate() === parts[2];
  }
}
