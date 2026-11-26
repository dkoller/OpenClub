import type { Language } from './language'

// Swiss dates and times in every language (product rule): "Sa 28.11.2026", "18:30", "26.12.2026".
const locales = { de: 'de-CH', en: 'en-GB', fr: 'fr-CH', it: 'it-CH' } as const
const pad = (value: number) => String(value).padStart(2, '0')

/** A calendar date ("2026-12-26") stays that day; an instant is shown in the browser's time zone. */
function toDate(value: string): Date {
  const day = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  return day ? new Date(Number(day[1]), Number(day[2]) - 1, Number(day[3])) : new Date(value)
}

/** Short weekday without a full stop: "Sa", "Sat", "sam", "sab". */
export function weekday(value: string | Date, language: Language): string {
  const date = typeof value === 'string' ? toDate(value) : value
  return new Intl.DateTimeFormat(locales[language], { weekday: 'short' }).format(date).replace('.', '')
}

/** "26.12.2026". */
export function formatDate(value: string | Date): string {
  const date = typeof value === 'string' ? toDate(value) : value
  return `${pad(date.getDate())}.${pad(date.getMonth() + 1)}.${date.getFullYear()}`
}

/** "18:30", always 24 hours. */
export function formatTime(value: string | Date): string {
  const date = typeof value === 'string' ? new Date(value) : value
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** "Sa 28.11.2026". */
export const formatDay = (value: string | Date, language: Language) => `${weekday(value, language)} ${formatDate(value)}`

/** "Sa 28.11.2026, 18:30". */
export const formatDayTime = (value: string, language: Language) => `${formatDay(value, language)}, ${formatTime(value)}`

/**
 * "Sa 28.11.2026, 17:30–19:00"; with `withDate: false` (next to a date block) only "17:30–19:00". An event that ends on
 * another day always shows the end date.
 */
export function formatRange(startsAt: string, endsAt: string, language: Language, withDate = true): string {
  const from = new Date(startsAt)
  const to = new Date(endsAt)
  const end = from.toDateString() === to.toDateString() ? formatTime(to) : formatDayTime(endsAt, language)
  return `${withDate ? formatDay(from, language) + ', ' : ''}${formatTime(from)}–${end}`
}
