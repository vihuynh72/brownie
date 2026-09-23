/**
 * The stretches of days a person can list from their calendar, counted in
 * their own time zone from the start of today, and each within the 31 days
 * the server reads at most. The server is sent the two instants with their
 * offsets, so it never has to guess what "today" means where the person is.
 */
export type WindowChoice = 'last7' | 'next7' | 'last30' | 'next30'

export const WINDOW_CHOICES: { value: WindowChoice; label: string }[] = [
  { value: 'last7', label: 'The last 7 days, today included' },
  { value: 'next7', label: 'The next 7 days, today included' },
  { value: 'last30', label: 'The last 30 days, today included' },
  { value: 'next30', label: 'The next 30 days, today included' },
]

function startOfDay(date: Date, plusDays: number): Date {
  const day = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  day.setDate(day.getDate() + plusDays)
  return day
}

export function calendarWindow(choice: WindowChoice, now: Date): { from: Date; to: Date } {
  switch (choice) {
    case 'last7':
      return { from: startOfDay(now, -6), to: startOfDay(now, 1) }
    case 'next7':
      return { from: startOfDay(now, 0), to: startOfDay(now, 7) }
    case 'last30':
      return { from: startOfDay(now, -29), to: startOfDay(now, 1) }
    case 'next30':
      return { from: startOfDay(now, 0), to: startOfDay(now, 30) }
  }
}
