export function nextMonthly(after: Date, interval: number, anchorDay: number): Date {
  const target = new Date(after.getFullYear(), after.getMonth() + interval, 1);
  const lastDay = new Date(target.getFullYear(), target.getMonth() + 1, 0).getDate();
  target.setDate(Math.min(anchorDay, lastDay));
  return target;
}

export function nextOccurrence(type: string, afterIso: string, interval: number, anchorDay: number): string | undefined {
  const after = new Date(`${afterIso}T00:00:00`);
  if (type === 'ONCE') return undefined;
  if (type === 'EVERY_N_DAYS') after.setDate(after.getDate() + interval);
  else if (type === 'EVERY_N_WEEKS') after.setDate(after.getDate() + interval * 7);
  else return formatDate(nextMonthly(after, interval, anchorDay));
  return formatDate(after);
}

export function formatDate(value: Date): string {
  const year = value.getFullYear(); const month = `${value.getMonth() + 1}`.padStart(2, '0'); const day = `${value.getDate()}`.padStart(2, '0');
  return `${year}-${month}-${day}`;
}
