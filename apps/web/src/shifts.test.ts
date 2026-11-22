import { describe, expect, it, vi } from 'vitest'
import { createShift, deleteShift, parseShiftList, parseUnfilled, readShifts, readUnfilled, replaceShift, volunteer } from './shifts'

const list = {
  items: [{ id: 's1', title: 'Bar', startsAt: '2030-01-01T10:00:00Z', endsAt: '2030-01-01T12:00:00Z', needed: 2,
    volunteers: ['Ada Example'], signedUp: ['m1'] }],
  people: [{ id: 'm1', firstName: 'Ada' }], canManage: false,
}
const res = (status: number, body?: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body })

describe('volunteer shifts', () => {
  it('accepts exactly the documented list', () => {
    expect(parseShiftList(list)).toEqual(list)
    const item = list.items[0]
    for (const broken of [{ ...list, extra: 1 }, { ...list, canManage: 'no' }, { ...list, items: [{ ...item, needed: 0 }] },
      { ...list, items: [{ ...item, volunteers: [1] }] }, { ...list, items: [{ ...item, email: 'x' }] },
      { ...list, people: [{ id: 'm1' }] }, [], null]) {
      expect(() => parseShiftList(broken)).toThrow()
    }
  })

  it('reads uncached and maps refusals', async () => {
    const request = vi.fn().mockResolvedValue(res(200, list))
    expect(await readShifts('e/1', request)).toEqual(list)
    expect(request.mock.calls[0]).toEqual(['/api/v1/events/e%2F1/shifts', { credentials: 'same-origin', cache: 'no-store' }])
    for (const status of [401, 403, 404]) expect(await readShifts('e', vi.fn().mockResolvedValue(res(status)))).toBe('denied')
    await expect(readShifts('e', vi.fn().mockResolvedValue(res(503)))).rejects.toThrow()
  })

  it('writes with CSRF and maps every outcome', async () => {
    const input = { title: 'Bar', startsAt: 'a', endsAt: 'b', needed: 2 }
    const request = vi.fn().mockResolvedValue(res(201))
    expect(await createShift('e1', input, 'c', request)).toBe('ok')
    expect(request.mock.calls[0]).toEqual(['/api/v1/events/e1/shifts', { method: 'POST', credentials: 'same-origin',
      headers: { 'X-CSRF-Token': 'c', 'Content-Type': 'application/json' }, body: JSON.stringify(input) }])
    const put = vi.fn().mockResolvedValue(res(204))
    expect(await replaceShift('s1', input, 'c', put)).toBe('ok')
    expect(put.mock.calls[0][1]).toMatchObject({ method: 'PUT' })
    const del = vi.fn().mockResolvedValue(res(204))
    expect(await deleteShift('s1', 'c', del)).toBe('ok')
    expect(del.mock.calls[0]).toEqual(['/api/v1/shifts/s1', { method: 'DELETE', credentials: 'same-origin', headers: { 'X-CSRF-Token': 'c' } }])
    const up = vi.fn().mockResolvedValue(res(204))
    expect(await volunteer('s1', 'm/1', false, 'c', up)).toBe('ok')
    expect(up.mock.calls[0][0]).toBe('/api/v1/shifts/s1/volunteers/m%2F1')
    expect(up.mock.calls[0][1]).toMatchObject({ method: 'PUT' })
    expect((await volunteer('s1', 'm1', true, 'c', vi.fn().mockResolvedValue(res(204))))).toBe('ok')
    for (const [status, body, outcome] of [[400, undefined, 'invalid'], [403, undefined, 'denied'], [404, undefined, 'denied'],
      [409, { code: 'full' }, 'full'], [409, { code: 'closed' }, 'closed'], [409, { code: 'conflict' }, 'conflict'],
      [409, undefined, 'closed']] as const) {
      expect(await volunteer('s1', 'm1', false, 'c', vi.fn().mockResolvedValue(res(status, body)))).toBe(outcome)
    }
    await expect(volunteer('s1', 'm1', false, 'c', vi.fn().mockResolvedValue(res(500)))).rejects.toThrow()
  })
})

describe('unfilled shifts overview', () => {
  const item = { id: 's', title: 'Bar', startsAt: '2026-11-29T09:00:00Z', endsAt: '2026-11-29T12:00:00Z', needed: 4, filled: 2,
    eventId: 'e', eventTitle: 'Hallenturnier', teamId: 't', teamName: 'U12' }
  const reply = (status: number, body: unknown = {}) => vi.fn(async () => new Response(JSON.stringify(body), { status })) as unknown as typeof fetch

  it('accepts exactly the documented counts-only shape', () => {
    expect(parseUnfilled({ items: [item], truncated: false }).items[0]!.filled).toBe(2)
    expect(() => parseUnfilled({ items: [{ ...item, volunteers: ['Mara Demo'] }], truncated: false })).toThrow()
    expect(() => parseUnfilled({ items: [{ ...item, filled: 4 }], truncated: false })).toThrow()
    expect(() => parseUnfilled({ items: [item] })).toThrow()
  })

  it('reads with the session, maps denial and fails on server errors', async () => {
    const ok = reply(200, { items: [], truncated: false })
    expect(await readUnfilled(ok)).toEqual({ items: [], truncated: false })
    expect(ok).toHaveBeenCalledWith('/api/v1/shifts/unfilled', { credentials: 'same-origin', cache: 'no-store' })
    expect(await readUnfilled(reply(403))).toBe('denied')
    expect(await readUnfilled(reply(401))).toBe('denied')
    await expect(readUnfilled(reply(503))).rejects.toThrow()
  })
})
