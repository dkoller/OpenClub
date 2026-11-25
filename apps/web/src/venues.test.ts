import { describe, expect, it, vi } from 'vitest'
import { changeVenue, readBookings, readVenues } from './venues'

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
})
