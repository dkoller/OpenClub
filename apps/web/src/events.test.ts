import { describe, expect, it, vi } from 'vitest'
import {
  answerable, answerAttendance, clearAttendance, createEvents, deleteEvent, formatWhen, instants, localParts, readAttendance,
  readMyEvents, readTeamEvents, replaceEvent,
} from './events'

const event = {
  id: 'e1', teamId: 't1', teamName: 'U10', kind: 'TRAINING', title: 'Training', startsAt: '2026-11-26T17:00:00Z',
  endsAt: '2026-11-26T18:30:00Z', location: 'Hall', notes: null, cancelled: false, counts: { yes: 2, no: 0, maybe: 1 },
}
const res = (status: number, body?: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body })

describe('events client', () => {
  it('reads my events strictly', async () => {
    const mine = { ...event, for: [{ id: 'k1', firstName: 'Kim', response: 'YES' }] }
    expect(await readMyEvents(vi.fn().mockResolvedValue(res(200, { items: [mine], truncated: false }))))
      .toEqual({ items: [mine], truncated: false })
    expect(await readMyEvents(vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    for (const broken of [{ ...mine, extra: 1 }, { ...mine, for: [{ id: 'k1', firstName: 'Kim' }] }, { ...mine, kind: 'PARTY' },
      { ...mine, for: [{ id: 'k1', firstName: 'Kim', response: 'yes' }] }, { ...mine, counts: { yes: -1, no: 0, maybe: 0 } },
      { ...mine, counts: undefined },
      { ...mine, startsAt: 'soon' }, event]) {
      await expect(readMyEvents(vi.fn().mockResolvedValue(res(200, { items: [broken], truncated: false })))).rejects.toThrow()
    }
    await expect(readMyEvents(vi.fn().mockResolvedValue(res(200, { items: [] })))).rejects.toThrow()
  })

  it('reads a team schedule with canManage', async () => {
    const request = vi.fn().mockResolvedValue(res(200, { items: [event], truncated: true, canManage: true }))
    expect(await readTeamEvents('a/b', request)).toEqual({ items: [event], truncated: true, canManage: true })
    expect(request.mock.calls[0][0]).toBe('/api/v1/teams/a%2Fb/events')
    await expect(readTeamEvents('t', vi.fn().mockResolvedValue(res(200, { items: [], truncated: false })))).rejects.toThrow()
    await expect(readTeamEvents('t', vi.fn().mockResolvedValue(res(503)))).rejects.toThrow()
  })

  it('writes events with CSRF, trimmed optional text and the repeat zone', async () => {
    const request = vi.fn().mockResolvedValue(res(201, { ids: ['e1'] }))
    const input = { kind: 'MATCH' as const, title: ' Cup ', startsAt: 'a', endsAt: 'b', location: '  ', notes: ' Bring shoes ' }
    expect(await createEvents('t1', input, '2026-12-24', 'c', request, 'Europe/Zurich')).toBe('ok')
    expect(request.mock.calls[0][0]).toBe('/api/v1/teams/t1/events')
    expect(JSON.parse(request.mock.calls[0][1].body)).toEqual({ kind: 'MATCH', title: 'Cup', startsAt: 'a', endsAt: 'b',
      notes: 'Bring shoes', repeatWeeklyUntil: '2026-12-24', timeZone: 'Europe/Zurich' })
    expect(request.mock.calls[0][1].headers).toMatchObject({ 'X-CSRF-Token': 'c' })
    const single = vi.fn().mockResolvedValue(res(201, { ids: ['e1'] }))
    await createEvents('t1', input, '', 'c', single)
    expect(JSON.parse(single.mock.calls[0][1].body)).not.toHaveProperty('timeZone')
    const put = vi.fn().mockResolvedValue(res(204))
    expect(await replaceEvent('e/1', input, true, 'c', put)).toBe('ok')
    expect(put.mock.calls[0][0]).toBe('/api/v1/events/e%2F1')
    expect(JSON.parse(put.mock.calls[0][1].body).cancelled).toBe(true)
    for (const [status, outcome] of [[400, 'invalid'], [401, 'denied'], [403, 'denied'], [404, 'not-found']] as const) {
      expect(await deleteEvent('e1', 'c', vi.fn().mockResolvedValue(res(status)))).toBe(outcome)
    }
    await expect(deleteEvent('e1', 'c', vi.fn().mockResolvedValue(res(503)))).rejects.toThrow()
  })

  it('converts form times and formats them', () => {
    const times = instants('2026-11-26', '18:00', '19:30')!
    expect(new Date(times.endsAt).getTime() - new Date(times.startsAt).getTime()).toBe(90 * 60 * 1000)
    expect(instants('2026-11-26', '18:00', '18:00')).toBeNull()
    expect(instants('', '18:00', '19:00')).toBeNull()
    expect(localParts(times)).toEqual({ date: '2026-11-26', start: '18:00', end: '19:30' })
    expect(formatWhen(times, 'en')).toMatch(/26 Nov, 18:00–19:30$/)
  })

  it('answers, clears and lists attendance', async () => {
    const put = vi.fn().mockResolvedValue(res(204))
    expect(await answerAttendance('e/1', 'm 1', 'MAYBE', 'c', put)).toBe('ok')
    expect(put.mock.calls[0][0]).toBe('/api/v1/events/e%2F1/attendance/m%201')
    expect(put.mock.calls[0][1]).toMatchObject({ method: 'PUT', body: '{"response":"MAYBE"}', headers: { 'X-CSRF-Token': 'c' } })
    const del = vi.fn().mockResolvedValue(res(204))
    expect(await clearAttendance('e1', 'm1', 'c', del)).toBe('ok')
    expect(del.mock.calls[0][1]).toMatchObject({ method: 'DELETE' })
    expect(del.mock.calls[0][1]).not.toHaveProperty('body')
    expect(await answerAttendance('e1', 'm1', 'YES', 'c', vi.fn().mockResolvedValue(res(409)))).toBe('closed')
    expect(await clearAttendance('e1', 'm1', 'c', vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    await expect(answerAttendance('e1', 'm1', 'NO', 'c', vi.fn().mockResolvedValue(res(500)))).rejects.toThrow()

    const list = { items: [{ displayName: 'Kim K.', response: null }, { displayName: 'Ada Example', response: 'NO' }],
      counts: { yes: 0, no: 1, maybe: 0 } }
    expect(await readAttendance('e1', vi.fn().mockResolvedValue(res(200, list)))).toEqual(list)
    expect(await readAttendance('e1', vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    for (const broken of [{ ...list, extra: 1 }, { ...list, items: [{ displayName: 'x', response: 'NO', id: 'm1' }] },
      { ...list, items: [{ displayName: 'x', response: 'LATE' }] }]) {
      await expect(readAttendance('e1', vi.fn().mockResolvedValue(res(200, broken)))).rejects.toThrow()
    }
  })

  it('closes answers at the start and for cancelled events', () => {
    const now = Date.parse('2026-11-26T16:00:00Z')
    expect(answerable(event, now)).toBe(true)
    expect(answerable({ ...event, cancelled: true }, now)).toBe(false)
    expect(answerable(event, Date.parse('2026-11-26T17:00:00Z'))).toBe(false)
  })
})

describe('attendance statistics', () => {
  const stats = { from: '2025-03-01', to: '2025-03-31', held: 2,
    items: [{ displayName: 'Ada Example', yes: 1, no: 0, maybe: 0, noAnswer: 1 }],
    totals: { yes: 1, no: 0, maybe: 0, noAnswer: 1 } }
  const res = (status: number, body?: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body })

  it('reads the range uncached and validates the shape', async () => {
    const { readAttendanceStatistics } = await import('./events')
    const request = vi.fn().mockResolvedValue(res(200, stats))
    expect(await readAttendanceStatistics('t/1', '2025-03-01', '2025-03-31', request)).toEqual(stats)
    expect(request.mock.calls[0]).toEqual(['/api/v1/teams/t%2F1/attendance-statistics?from=2025-03-01&to=2025-03-31',
      { credentials: 'same-origin', cache: 'no-store' }])
    const defaults = vi.fn().mockResolvedValue(res(200, stats))
    await readAttendanceStatistics('t1', undefined, undefined, defaults)
    expect(defaults.mock.calls[0][0]).toBe('/api/v1/teams/t1/attendance-statistics')
    expect(await readAttendanceStatistics('t1', 'x', undefined, vi.fn().mockResolvedValue(res(400)))).toBe('invalid')
    expect(await readAttendanceStatistics('t1', undefined, undefined, vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    for (const broken of [{ ...stats, extra: 1 }, { ...stats, held: -1 }, { ...stats, totals: { yes: 1 } },
      { ...stats, items: [{ displayName: 'A', yes: 1, no: 0, maybe: 0, noAnswer: 0, email: 'x' }] }]) {
      await expect(readAttendanceStatistics('t1', undefined, undefined, vi.fn().mockResolvedValue(res(200, broken)))).rejects.toThrow()
    }
  })
})
