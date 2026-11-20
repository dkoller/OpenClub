/** Carpooling for team events (OpenAPI 1.21.0). */
export type Direction = 'THERE' | 'BACK' | 'BOTH'
export type Ride = {
  id: string; driver: string; mine: boolean; direction: Direction; seats: number; meetingPoint: string; departsAt: string
  /** Only for a caller who booked a seat on this ride. */
  driverPhone: string | null
  /** A passenger's phone only for the driver. */
  passengers: { displayName: string; phone: string | null }[]
  booked: string[]
}
export type RideList = { items: Ride[]; people: { id: string; firstName: string }[]; canOffer: boolean }
export type RideInput = { direction: Direction; seats: number; meetingPoint: string; departsAt: string }

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
const keys = (value: Record<string, unknown>, expected: string[]) =>
  Object.keys(value).length === expected.length && expected.every(key => key in value)
const text = (value: unknown): value is string => typeof value === 'string'
const textOrNull = (value: unknown) => value === null || text(value)

export function parseRides(body: unknown): RideList {
  const valid = record(body) && keys(body, ['items', 'people', 'canOffer']) && typeof body.canOffer === 'boolean' &&
    Array.isArray(body.people) && body.people.every(p => record(p) && keys(p, ['id', 'firstName']) && text(p.id) && text(p.firstName)) &&
    Array.isArray(body.items) && body.items.every(r => record(r) &&
      keys(r, ['id', 'driver', 'mine', 'direction', 'seats', 'meetingPoint', 'departsAt', 'driverPhone', 'passengers', 'booked']) &&
      text(r.id) && text(r.driver) && typeof r.mine === 'boolean' && ['THERE', 'BACK', 'BOTH'].includes(r.direction as string) &&
      typeof r.seats === 'number' && Number.isInteger(r.seats) && r.seats > 0 && text(r.meetingPoint) && text(r.departsAt) &&
      textOrNull(r.driverPhone) && Array.isArray(r.booked) && r.booked.every(text) &&
      Array.isArray(r.passengers) && r.passengers.every(p => record(p) && keys(p, ['displayName', 'phone']) &&
        text(p.displayName) && textOrNull(p.phone)))
  if (!valid) throw new Error('Invalid rides')
  return body as RideList
}

export async function readRides(eventId: string, request: typeof fetch = fetch): Promise<RideList | 'denied'> {
  const response = await request('/api/v1/events/' + encodeURIComponent(eventId) + '/rides', { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403 || response.status === 404) return 'denied'
  if (!response.ok) throw new Error('Rides unavailable')
  return parseRides(await response.json())
}

/** 'exists': one offer per event; 'closed': departed or cancelled; 'full': no seat; 'conflict': fewer seats than bookings. */
export type RideOutcome = 'ok' | 'invalid' | 'denied' | 'exists' | 'closed' | 'full' | 'conflict'

async function write(path: string, method: 'POST' | 'PUT' | 'DELETE', csrfToken: string, body: object | undefined,
  request: typeof fetch): Promise<RideOutcome> {
  const response = await request(path, {
    method, credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  if (response.ok) return 'ok'
  if (response.status === 400) return 'invalid'
  if (response.status === 401 || response.status === 403 || response.status === 404) return 'denied'
  if (response.status === 409) {
    const code = await response.json().then(value => (record(value) ? value.code : null), () => null)
    return code === 'exists' || code === 'full' || code === 'conflict' ? code : 'closed'
  }
  throw new Error('Request failed')
}

const ridePath = (id: string) => '/api/v1/rides/' + encodeURIComponent(id)
export const offerRide = (eventId: string, input: RideInput, csrfToken: string, request: typeof fetch = fetch) =>
  write('/api/v1/events/' + encodeURIComponent(eventId) + '/rides', 'POST', csrfToken, input, request)
export const changeRide = (rideId: string, input: RideInput, csrfToken: string, request: typeof fetch = fetch) =>
  write(ridePath(rideId), 'PUT', csrfToken, input, request)
export const withdrawRide = (rideId: string, csrfToken: string, request: typeof fetch = fetch) =>
  write(ridePath(rideId), 'DELETE', csrfToken, undefined, request)
/** Books (or, with [cancel], cancels) a seat for the caller or their child. */
export const seat = (rideId: string, memberId: string, cancel: boolean, csrfToken: string, request: typeof fetch = fetch) =>
  write(ridePath(rideId) + '/passengers/' + encodeURIComponent(memberId), cancel ? 'DELETE' : 'PUT', csrfToken, undefined, request)
