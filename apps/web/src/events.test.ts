import { describe, expect, it, vi } from 'vitest'
import { createEvents, deleteEvent, formatWhen, instants, localParts, readMyEvents, readTeamEvents, replaceEvent } from './events'

const event = {
  id: 'e1', teamId: 't1', teamName: 'U10', kind: 'TRAINING', title: 'Training', startsAt: '2026-11-26T17:00:00Z',
  endsAt: '2026-11-26T18:30:00Z', location: 'Hall', notes: null, cancelled: false,
}
const res = (status: number, body?: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body })

describe('events client', () => {
  it('reads my events strictly', async () => {
    const mine = { ...event, for: [{ id: 'k1', firstName: 'Kim' }] }
    expect(await readMyEvents(vi.fn().mockResolvedValue(res(200, { items: [mine], truncated: false }))))
      .toEqual({ items: [mine], truncated: false })
    expect(await readMyEvents(vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    for (const broken of [{ ...mine, extra: 1 }, { ...mine, for: [{ id: 'k1' }] }, { ...mine, kind: 'PARTY' },
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
})
