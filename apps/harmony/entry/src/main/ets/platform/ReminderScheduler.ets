import { reminderAgentManager } from '@kit.BackgroundTasksKit';
import { notificationManager } from '@kit.NotificationKit';
import { HealthState } from '../model/Models';

export class ReminderScheduler {
  static async requestPermission(): Promise<boolean> {
    try { await notificationManager.requestEnableNotification(); return true; } catch (_) { return false; }
  }

  static async rebuild(state: HealthState): Promise<void> {
    const existing = await reminderAgentManager.getValidReminders();
    for (const reminder of existing) await reminderAgentManager.cancelReminder(reminder.reminderId);
    for (const item of state.followUps.filter(value => value.enabled)) {
      const date = new Date(`${item.nextDueDate}T${item.reminderTime}:00`);
      date.setDate(date.getDate() - item.leadDays);
      const request: reminderAgentManager.ReminderRequestCalendar = {
        reminderType: reminderAgentManager.ReminderType.REMINDER_TYPE_CALENDAR,
        dateTime: { year: date.getFullYear(), month: date.getMonth() + 1, day: date.getDate(), hour: date.getHours(), minute: date.getMinutes(), second: 0 },
        actionButton: [{ title: '完成', type: reminderAgentManager.ActionButtonType.ACTION_BUTTON_TYPE_CLOSE }],
        wantAgent: { pkgName: 'com.healthtimeline.app.harmony', abilityName: 'EntryAbility' },
        title: '病程日历提醒', content: '您有一项复查计划需要处理', expiredContent: '复查计划仍未处理', snoozeContent: '复查提醒'
      };
      await reminderAgentManager.publishReminder(request);
    }
  }
}
