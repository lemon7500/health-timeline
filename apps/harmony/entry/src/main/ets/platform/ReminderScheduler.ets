import { reminderAgentManager } from '@kit.BackgroundTasksKit';
import { notificationManager } from '@kit.NotificationKit';
import { HealthState } from '../model/Models';

export interface ReminderRebuildResult { scheduled: number; failed: number }

export class ReminderScheduler {
  static async requestPermission(): Promise<boolean> {
    try { await notificationManager.requestEnableNotification(); return true; } catch (_) { return false; }
  }

  static async rebuild(state: HealthState): Promise<ReminderRebuildResult> {
    const existing = await reminderAgentManager.getValidReminders();
    for (const reminder of existing) await reminderAgentManager.cancelReminder(reminder.reminderId);
    const activeMembers: Set<string> = new Set(state.members.filter(value => !value.archived).map(value => value.uuid));
    let published = 0;
    let failed = 0;
    for (const item of state.followUps.filter(value => value.enabled && activeMembers.has(value.memberUuid))) {
      const date = new Date(`${item.nextDueDate}T${item.reminderTime}:00`);
      date.setDate(date.getDate() - item.leadDays);
      const request: reminderAgentManager.ReminderRequestCalendar = {
        reminderType: reminderAgentManager.ReminderType.REMINDER_TYPE_CALENDAR,
        dateTime: { year: date.getFullYear(), month: date.getMonth() + 1, day: date.getDate(), hour: date.getHours(), minute: date.getMinutes(), second: 0 },
        actionButton: [{ title: '完成', type: reminderAgentManager.ActionButtonType.ACTION_BUTTON_TYPE_CLOSE }],
        wantAgent: { pkgName: 'com.healthtimeline.app.harmony', abilityName: 'EntryAbility' },
        title: '病程日历提醒', content: '您有一项复查计划需要处理', expiredContent: '复查计划仍未处理', snoozeContent: '复查提醒'
      };
      try { await reminderAgentManager.publishReminder(request); published++; } catch (_) { failed++; }
      if (published >= 60) return { scheduled: published, failed: failed };
    }
    const medications = new Map(state.medications.map(item => [item.uuid, item]));
    const now = new Date();
    for (let offset = 0; offset < 35 && published < 60; offset++) {
      const day = new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset);
      const dayIso = `${day.getFullYear()}-${`${day.getMonth() + 1}`.padStart(2, '0')}-${`${day.getDate()}`.padStart(2, '0')}`;
      for (const schedule of state.medicationSchedules.filter(item => item.enabled && item.effectiveFrom <= dayIso && (!item.effectiveTo || item.effectiveTo >= dayIso))) {
        const medication = medications.get(schedule.medicationUuid);
        if (!medication || medication.archived || !activeMembers.has(medication.memberUuid) || medication.startDate > dayIso || (medication.endDate && medication.endDate < dayIso)) continue;
        const parts = schedule.localTime.split(':').map(value => Number(value));
        const date = new Date(day.getFullYear(), day.getMonth(), day.getDate(), parts[0], parts[1], 0);
        if (date <= now) continue;
        const request: reminderAgentManager.ReminderRequestCalendar = {
          reminderType: reminderAgentManager.ReminderType.REMINDER_TYPE_CALENDAR,
          dateTime: { year: date.getFullYear(), month: date.getMonth() + 1, day: date.getDate(), hour: date.getHours(), minute: date.getMinutes(), second: 0 },
          actionButton: [{ title: '知道了', type: reminderAgentManager.ActionButtonType.ACTION_BUTTON_TYPE_CLOSE }],
          wantAgent: { pkgName: 'com.healthtimeline.app.harmony', abilityName: 'EntryAbility' },
          title: '病程日历提醒', content: '您有一项用药计划需要处理', expiredContent: '用药计划仍未处理', snoozeContent: '用药提醒'
        };
        try { await reminderAgentManager.publishReminder(request); published++; } catch (_) { failed++; }
        if (published >= 60) return { scheduled: published, failed: failed };
      }
    }
    return { scheduled: published, failed: failed };
  }
}
