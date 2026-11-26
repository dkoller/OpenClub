import { describe, expect, it } from 'vitest'
import { formatDate, formatDay, formatRange, formatTime } from './dates'

describe('Swiss dates', () => {
  it('formats calendar dates and instants the Swiss way in every language', () => {
    expect(formatDate('2026-12-26')).toBe('26.12.2026')
    expect(formatTime(new Date(2026, 10, 28, 9, 5))).toBe('09:05')
    expect(formatDay('2026-11-28', 'de')).toBe('Sa 28.11.2026')
    expect(formatDay('2026-11-28', 'en')).toBe('Sat 28.11.2026')
  })

  it('shows a range with or without the date, and the end date across days', () => {
    const start = new Date(2026, 10, 28, 17, 30).toISOString()
    const end = new Date(2026, 10, 28, 19, 0).toISOString()
    expect(formatRange(start, end, 'de')).toBe('Sa 28.11.2026, 17:30–19:00')
    expect(formatRange(start, end, 'de', false)).toBe('17:30–19:00')
    const nextDay = new Date(2026, 10, 29, 10, 0).toISOString()
    expect(formatRange(start, nextDay, 'de', false)).toBe('17:30–So 29.11.2026, 10:00')
  })
})
