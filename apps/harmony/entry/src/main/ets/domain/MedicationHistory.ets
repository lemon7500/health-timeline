import { HealthState, Medication, MedicationLog, MedicationSchedule } from '../model/Models';

export interface MedicationDayEntry {
  medication: Medication;
  schedule?: MedicationSchedule;
  log?: MedicationLog;
  plannedAt: string;
  doseAmount: string;
  doseUnit: string;
}

// Notification enablement is not historical existence. A closed/paused version
// still describes the dose that was planned during its effective interval.
export class MedicationHistory {
  static entriesForDay(state: HealthState, memberUuid: string, day: string): MedicationDayEntry[] {
    if (!state.members.some(item => item.uuid === memberUuid && !item.archived)) return [];
    const result: MedicationDayEntry[] = [];
    state.medications.filter(item => item.memberUuid === memberUuid).forEach(medication => {
      const logs = state.medicationLogs.filter(item => item.medicationUuid === medication.uuid && item.scheduledAt.substring(0, 10) === day);
      const used = new Set<string>();
      if (medication.startDate <= day && (!medication.endDate || medication.endDate >= day)) {
        state.medicationSchedules.filter(item => item.medicationUuid === medication.uuid && item.effectiveFrom <= day && (!item.effectiveTo || item.effectiveTo >= day)).forEach(schedule => {
          const plannedAt = `${day}T${schedule.localTime}:00`;
          const log = logs.find(item => item.scheduleUuid === schedule.uuid && this.doseKey(item.scheduledAt) === this.doseKey(plannedAt));
          if (log) used.add(log.uuid);
          result.push({ medication: medication, schedule: schedule, log: log, plannedAt: plannedAt,
            doseAmount: log ? log.doseAmountSnapshot : schedule.doseAmountSnapshot,
            doseUnit: log ? log.doseUnitSnapshot : schedule.doseUnitSnapshot });
        });
      }
      // Includes as-needed doses and historical logs outside a subsequently
      // shortened course; never hide a saved dose or substitute today's dose.
      logs.filter(item => !used.has(item.uuid)).forEach(log => result.push({ medication: medication, log: log, plannedAt: log.scheduledAt, doseAmount: log.doseAmountSnapshot, doseUnit: log.doseUnitSnapshot }));
    });
    return result.sort((left, right) => left.plannedAt.localeCompare(right.plannedAt) || (left.log?.actualAt ?? '').localeCompare(right.log?.actualAt ?? '') || left.medication.uuid.localeCompare(right.medication.uuid));
  }

  static endCourse(state: HealthState, memberUuid: string, medicationUuid: string, today: string, now: string): void {
    const medication = this.ownedMedication(state, memberUuid, medicationUuid);
    if (medication.archived) throw new Error('疗程已经结束，请刷新后查看');
    if (medication.startDate > today) throw new Error('疗程尚未开始，不能在今天结束');
    if (medication.endDate && medication.endDate < today) throw new Error('疗程已过原结束日期，请核对历史日期');
    if (state.medicationSchedules.some(item => item.medicationUuid === medicationUuid && !item.enabled && !item.pausedByCourseEnd && (!item.effectiveTo || item.effectiveTo > today))) throw new Error('历史计划区间需核对，疗程未更改');
    if (state.medicationLogs.some(item => item.medicationUuid === medicationUuid && item.scheduledAt.substring(0, 10) > today)) throw new Error('存在未来用药记录，请先核对，疗程未更改');
    medication.archivedPreviousEndDate = medication.endDate;
    medication.endDate = today; medication.endedAt = now; medication.archived = true; medication.updatedAt = now;
    state.medicationSchedules.filter(item => item.medicationUuid === medicationUuid && item.enabled).forEach(item => {
      item.enabled = false;
      // Retain the original interval for restoration and v4 backup compatibility.
      item.pausedByCourseEnd = true;
      item.updatedAt = now;
    });
  }

  static restoreCourse(state: HealthState, memberUuid: string, medicationUuid: string, now: string): void {
    const medication = this.ownedMedication(state, memberUuid, medicationUuid);
    if (!medication.archived || !medication.endedAt) throw new Error('此疗程没有可恢复的结束操作');
    const schedules = state.medicationSchedules.filter(item => item.medicationUuid === medicationUuid);
    if (medication.mode === 'SCHEDULED' && !schedules.some(item => item.pausedByCourseEnd)) throw new Error('旧疗程缺少暂停标记，请先核对计划，不能猜测恢复');
    medication.endDate = medication.archivedPreviousEndDate;
    medication.archivedPreviousEndDate = undefined; medication.endedAt = undefined; medication.archived = false; medication.updatedAt = now;
    schedules.filter(item => item.pausedByCourseEnd).forEach(item => { item.enabled = true; item.pausedByCourseEnd = false; item.updatedAt = now; });
  }

  static changeSchedule(state: HealthState, memberUuid: string, medicationUuid: string, name: string, amount: string, unit: string, time: string, today: string, tomorrow: string, now: string, scheduleUuid: string): void {
    const medication = this.ownedMedication(state, memberUuid, medicationUuid);
    if (medication.archived || (medication.endDate && medication.endDate < tomorrow)) throw new Error('疗程已结束或明日已不在疗程内，不能新增明日计划');
    if (medication.startDate > tomorrow || !name.trim() || !amount.trim() || !unit.trim() || !this.validTime(time)) throw new Error('请核对药名、剂量和生效日期');
    const future = state.medicationSchedules.filter(item => item.medicationUuid === medicationUuid && item.effectiveFrom >= tomorrow);
    if (state.medicationLogs.some(item => item.medicationUuid === medicationUuid && (item.scheduledAt.substring(0, 10) >= tomorrow || future.some(schedule => schedule.uuid === item.scheduleUuid)))) throw new Error('未来计划已有记录，不能覆盖其历史');
    state.medicationSchedules = state.medicationSchedules.filter(item => !future.some(schedule => schedule.uuid === item.uuid));
    state.medicationSchedules.filter(item => item.medicationUuid === medicationUuid && (!item.effectiveTo || item.effectiveTo >= tomorrow)).forEach(item => { item.effectiveTo = today; item.updatedAt = now; });
    // Keep today's version enabled until its own effectiveTo. Otherwise editing
    // a tomorrow plan cancels today's remaining local notifications.
    medication.name = name.trim(); medication.doseAmount = amount.trim(); medication.doseUnit = unit.trim(); medication.updatedAt = now;
    state.medicationSchedules.push({ uuid: scheduleUuid, medicationUuid: medicationUuid, localTime: time, enabled: true, effectiveFrom: tomorrow, effectiveTo: medication.endDate, doseAmountSnapshot: amount.trim(), doseUnitSnapshot: unit.trim(), updatedAt: now, pausedByCourseEnd: false });
  }

  static recordDose(state: HealthState, memberUuid: string, medicationUuid: string, scheduleUuid: string, day: string, status: string, actualTime: string, today: string, now: string, logUuid: string, expectedLogUuid: string = '', expectedUpdatedAt: string = ''): void {
    const medication = this.ownedMedication(state, memberUuid, medicationUuid);
    if (!this.validDay(day) || day > today || !['TAKEN', 'SKIPPED'].includes(status)) throw new Error('未来日期或无效状态不能打卡');
    if (status === 'TAKEN' && (!this.validTime(actualTime) || Number(actualTime.substring(3)) % 5 !== 0)) throw new Error('请选择每 5 分钟一档的实际时间');
    const actualAt = status === 'TAKEN' ? `${day}T${actualTime}:00` : undefined;
    if (actualAt && Date.parse(actualAt) > Date.parse(now)) throw new Error('实际服用时间不能在未来');
    const existing = expectedLogUuid ? state.medicationLogs.find(item => item.uuid === expectedLogUuid) : undefined;
    if (expectedLogUuid && (!existing || existing.medicationUuid !== medicationUuid || existing.scheduledAt.substring(0, 10) !== day || (existing.scheduleUuid ?? '') !== scheduleUuid || (expectedUpdatedAt && existing.updatedAt !== expectedUpdatedAt))) throw new Error('原记录已变化，请重新打开后核对');
    if (existing) {
      // Corrections preserve the original identity, planned time and dose snapshot,
      // including records belonging to an already ended course.
      existing.status = status; existing.actualAt = actualAt; existing.updatedAt = now; return;
    }
    if (medication.archived || day < medication.startDate || (medication.endDate && day > medication.endDate)) throw new Error('疗程已结束或日期不在疗程内，不能新增打卡');
    const schedule = scheduleUuid ? state.medicationSchedules.find(item => item.uuid === scheduleUuid) : undefined;
    if (scheduleUuid && (!schedule || schedule.medicationUuid !== medicationUuid || schedule.effectiveFrom > day || (schedule.effectiveTo && schedule.effectiveTo < day))) throw new Error('用药计划已变化，请刷新后核对');
    if (!schedule && medication.mode !== 'AS_NEEDED') throw new Error('计划用药必须选择有效的历史时间表');
    if (!schedule && status !== 'TAKEN') throw new Error('按需用药不生成虚构的跳过记录');
    if (expectedUpdatedAt && (schedule ? schedule.updatedAt : medication.updatedAt) !== expectedUpdatedAt) throw new Error('剂量或计划已变化，请重新核对');
    if (!schedule) {
      const updated = new Date(medication.updatedAt);
      const knownFrom = `${updated.getFullYear()}-${`${updated.getMonth() + 1}`.padStart(2, '0')}-${`${updated.getDate()}`.padStart(2, '0')}`;
      if (!Number.isFinite(updated.getTime()) || day < knownFrom) throw new Error('此日期缺少可验证的按需剂量历史，请先核对，不使用当前剂量猜测');
    }
    const scheduledAt = schedule ? `${day}T${schedule.localTime}:00` : `${day}T${actualTime}:00`;
    if (state.medicationLogs.some(item => item.medicationUuid === medicationUuid && this.doseKey(item.scheduledAt) === this.doseKey(scheduledAt))) throw new Error('此剂已有记录，请核对原记录后修正');
    state.medicationLogs.push({ uuid: logUuid, medicationUuid: medicationUuid, scheduleUuid: schedule?.uuid, scheduledAt: scheduledAt, actualAt: actualAt, status: status, doseAmountSnapshot: schedule ? schedule.doseAmountSnapshot : medication.doseAmount, doseUnitSnapshot: schedule ? schedule.doseUnitSnapshot : medication.doseUnit, createdAt: now, updatedAt: now });
  }

  private static ownedMedication(state: HealthState, memberUuid: string, medicationUuid: string): Medication {
    if (state.selectedMemberUuid !== memberUuid || !state.members.some(item => item.uuid === memberUuid && !item.archived)) throw new Error('当前成员已变化，请重新核对');
    const medication = state.medications.find(item => item.uuid === medicationUuid && item.memberUuid === memberUuid);
    if (!medication) throw new Error('药物已删除或不属于此成员');
    return medication;
  }

  private static doseKey(value: string): string {
    // ISO local times with omitted zero seconds denote the same planned dose.
    return value.length === 16 ? `${value}:00` : value.replace(/\.0+$/, '');
  }

  private static validTime(value: string): boolean { return /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(value); }
  private static validDay(value: string): boolean {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    const parts = value.split('-').map(item => Number(item)); const date = new Date(parts[0], parts[1] - 1, parts[2]);
    return date.getFullYear() === parts[0] && date.getMonth() === parts[1] - 1 && date.getDate() === parts[2];
  }
}
