import { describe, expect, it, vi } from 'vitest'
import { changeRide, offerRide, parseRides, readRides, requestRide, seat, withdrawRide } from './rides'

const ride = { id: 'r1', driver: 'Ada Example', mine: false, direction: 'BOTH', seats: 3, meetingPoint: 'Station',
  departsAt: '2030-01-01T09:00:00Z', driverPhone: '+41 79 000 00 00', passengers: [{ displayName: 'Lea B.', phone: null }], booked: ['m1'] }
const list = { items: [ride], people: [{ id: 'm1', firstName: 'Lea' }], canOffer: true, requests: [{ displayName: 'Kim K.', direction: 'THERE' as const, memberId: null }] }
const res = (status: number, body?: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body })

describe('carpooling', () => {
  it('accepts exactly the documented list', () => {
    expect(parseRides(list)).toEqual(list)
    for (const broken of [{ ...list, extra: 1 }, { ...list, items: [{ ...ride, direction: 'ROUND' }] },
      { ...list, items: [{ ...ride, seats: 0 }] }, { ...list, items: [{ ...ride, email: 'x' }] },
      { ...list, items: [{ ...ride, passengers: [{ displayName: 'A' }] }] }, { ...list, canOffer: 'yes' }, { ...list, requests: [{ displayName: 'A', direction: 'UP', memberId: null }] }, { ...list, requests: undefined }, null]) {
      expect(() => parseRides(broken)).toThrow()
    }
  })

  it('reads uncached and maps every outcome', async () => {
    const read = vi.fn().mockResolvedValue(res(200, list))
    expect(await readRides('e/1', read)).toEqual(list)
    expect(read.mock.calls[0]).toEqual(['/api/v1/events/e%2F1/rides', { credentials: 'same-origin', cache: 'no-store' }])
    expect(await readRides('e', vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    const input = { direction: 'THERE' as const, seats: 2, meetingPoint: 'Station', departsAt: '2030-01-01T09:00:00Z' }
    const post = vi.fn().mockResolvedValue(res(201, { id: 'r2' }))
    expect(await offerRide('e1', input, 'c', post)).toBe('ok')
    expect(post.mock.calls[0]).toEqual(['/api/v1/events/e1/rides', { method: 'POST', credentials: 'same-origin',
      headers: { 'X-CSRF-Token': 'c', 'Content-Type': 'application/json' }, body: JSON.stringify(input) }])
    expect(await changeRide('r1', input, 'c', vi.fn().mockResolvedValue(res(409, { code: 'conflict' })))).toBe('conflict')
    expect(await offerRide('e1', input, 'c', vi.fn().mockResolvedValue(res(409, { code: 'exists' })))).toBe('exists')
    const del = vi.fn().mockResolvedValue(res(204))
    expect(await withdrawRide('r1', 'c', del)).toBe('ok')
    expect(del.mock.calls[0]).toEqual(['/api/v1/rides/r1', { method: 'DELETE', credentials: 'same-origin', headers: { 'X-CSRF-Token': 'c' } }])
    // Ride requests (1.41.0): PUT with the direction, DELETE without a body.
    const ask = vi.fn().mockResolvedValue(res(204))
    expect(await requestRide('e1', 'm/1', 'BACK', 'c', ask)).toBe('ok')
    expect(await requestRide('e1', 'm1', null, 'c', ask)).toBe('ok')
    expect(ask.mock.calls[0]).toEqual(['/api/v1/events/e1/ride-requests/m%2F1', { method: 'PUT', credentials: 'same-origin',
      headers: { 'X-CSRF-Token': 'c', 'Content-Type': 'application/json' }, body: '{"direction":"BACK"}' }])
    expect(ask.mock.calls[1][1]).toEqual({ method: 'DELETE', credentials: 'same-origin', headers: { 'X-CSRF-Token': 'c' } })
    const book = vi.fn().mockResolvedValue(res(204))
    expect(await seat('r1', 'm/1', false, 'c', book)).toBe('ok')
    expect(book.mock.calls[0][0]).toBe('/api/v1/rides/r1/passengers/m%2F1')
    expect(book.mock.calls[0][1]).toMatchObject({ method: 'PUT' })
    for (const [status, body, outcome] of [[409, { code: 'full' }, 'full'], [409, undefined, 'closed'], [400, undefined, 'invalid'],
      [404, undefined, 'denied']] as const) {
      expect(await seat('r1', 'm1', true, 'c', vi.fn().mockResolvedValue(res(status, body)))).toBe(outcome)
    }
    await expect(seat('r1', 'm1', false, 'c', vi.fn().mockResolvedValue(res(500)))).rejects.toThrow()
  })
})
