import { describe, expect, it, vi } from 'vitest'
import { byDay, changeVenue, isoWeek, readBookings, readVenues, readWeek, weekStart } from './venues'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

describe('venues', () => {
  it('reads the list and the bookings strictly', async () => {
    const list = { canManage: false, items: [{ id: 'v', name: 'Halle Süd' }] }
    expect(await readVenues(vi.fn().mockResolvedValue(json(list)))).toEqual(list)
    await expect(readVenues(vi.fn().mockResolvedValue(json({ items: [] })))).rejects.toThrow()
    const booking = { eventId: 'e', teamName: 'U12', kind: 'TRAINING', title: 'Training', startsAt: 'a', endsAt: 'b' }
    expect(await readBookings('v', 'f', 't', vi.fn().mockResolvedValue(json({ items: [booking] })))).toEqual([booking])
    await expect(readBookings('v', 'f', 't', vi.fn().mockResolvedValue(json({ items: [{ ...booking, phone: '1' }] })))).rejects.toThrow()
  })

  it('maps changes', async () => {
    const request = vi.fn().mockResolvedValue(new Response(null, { status: 201 }))
    expect(await changeVenue('POST', null, 'Halle', 'tok', request)).toBe('ok')
    expect(request.mock.calls[0]).toMatchObject(['/api/v1/venues', { method: 'POST', body: '{"name":"Halle"}' }])
    expect(await changeVenue('PUT', 'v', 'Halle', 't', vi.fn().mockResolvedValue(json({ code: 'duplicate' }, 409)))).toBe('duplicate')
    expect(await changeVenue('DELETE', 'v', null, 't', vi.fn().mockResolvedValue(json({}, 404)))).toBe('not-found')
  })

  it('plans a week from Monday', async () => {
    const thursday = new Date(2026, 10, 26, 15, 0)
    const monday = weekStart(thursday)
    expect([monday.getFullYear(), monday.getMonth(), monday.getDate(), monday.getHours()]).toEqual([2026, 10, 23, 0])
    expect(weekStart(new Date(2026, 10, 29), 1).getDate()).toBe(30)
    expect(isoWeek(monday)).toBe(48)
    expect(isoWeek(weekStart(new Date(2027, 0, 1)))).toBe(53)
    const at = (day: number, hour: number) => new Date(2026, 10, day, hour).toISOString()
    const booking = (venueName: string, day: number, hour: number) =>
      ({ eventId: venueName + day + hour, teamName: 'U12', kind: 'TRAINING' as const, title: 'Training', startsAt: at(day, hour), endsAt: at(day, hour + 1), venueName })
    const days = byDay(monday, [booking('Halle', 26, 18), booking('Feld', 26, 17), booking('Feld', 29, 10)])
    expect(days.map(day => day.items.length)).toEqual([0, 0, 0, 2, 0, 0, 1])
    expect(days[3].items.map(item => item.venueName)).toEqual(['Feld', 'Halle'])
  })

  it('reads one week of every venue', async () => {
    const request = vi.fn().mockImplementation(() => Promise.resolve(json({ items: [] })))
    const monday = new Date(2026, 10, 23)
    expect(await readWeek([{ id: 'a', name: 'A' }, { id: 'b', name: 'B' }], monday, request)).toEqual([])
    expect(request).toHaveBeenCalledTimes(2)
    expect(String(request.mock.calls[1][0])).toContain('/api/v1/venues/b/bookings?from=')
    expect(await readWeek([{ id: 'a', name: 'A' }], monday, vi.fn().mockResolvedValue(json({}, 404)))).toBe('denied')
  })
})
