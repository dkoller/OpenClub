/** Fields and halls (OpenAPI 1.30.0): the list for everyone, changes for MANAGE_VENUES holders. */
export type Venue = { id: string; name: string }
export type Booking = { eventId: string; teamName: string; kind: 'TRAINING' | 'MATCH' | 'OTHER'; title: string; startsAt: string; endsAt: string }

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

export async function readVenues(request: typeof fetch = fetch): Promise<{ canManage: boolean; items: Venue[] } | 'denied'> {
  const response = await request('/api/v1/venues', { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Venues unavailable')
  const body: unknown = await response.json()
  if (!record(body) || Object.keys(body).length !== 2 || typeof body.canManage !== 'boolean' || !Array.isArray(body.items) ||
    !body.items.every(item => record(item) && Object.keys(item).length === 2 && typeof item.id === 'string' && typeof item.name === 'string')) {
    throw new Error('Invalid venues')
  }
  return body as { canManage: boolean; items: Venue[] }
}

export async function readBookings(venueId: string, from: string, to: string, request: typeof fetch = fetch): Promise<Booking[] | 'denied'> {
  const query = new URLSearchParams({ from, to }).toString()
  const response = await request('/api/v1/venues/' + encodeURIComponent(venueId) + '/bookings?' + query,
    { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403 || response.status === 404) return 'denied'
  if (!response.ok) throw new Error('Bookings unavailable')
  const body: unknown = await response.json()
  const keys = ['eventId', 'teamName', 'kind', 'title', 'startsAt', 'endsAt']
  if (!record(body) || Object.keys(body).length !== 1 || !Array.isArray(body.items) || !body.items.every(item =>
    record(item) && Object.keys(item).length === keys.length && keys.every(key => typeof item[key] === 'string'))) {
    throw new Error('Invalid bookings')
  }
  return body.items as Booking[]
}

export type VenueOutcome = 'ok' | 'duplicate' | 'invalid' | 'denied' | 'not-found'

export async function changeVenue(method: 'POST' | 'PUT' | 'DELETE', venueId: string | null, name: string | null,
  csrfToken: string, request: typeof fetch = fetch): Promise<VenueOutcome> {
  const response = await request('/api/v1/venues' + (venueId ? '/' + encodeURIComponent(venueId) : ''), {
    method, credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken, ...(name !== null ? { 'Content-Type': 'application/json' } : {}) },
    ...(name !== null ? { body: JSON.stringify({ name }) } : {}),
  })
  if (response.ok) return 'ok'
  if (response.status === 409) return 'duplicate'
  if (response.status === 400) return 'invalid'
  if (response.status === 404) return 'not-found'
  if (response.status === 401 || response.status === 403) return 'denied'
  throw new Error('Request failed')
}

/** A booking in the week plan, with the name of its field or hall. */
export type PlannedBooking = Booking & { venueName: string }

/** Monday 00:00 (browser time) of the week that holds `day`, moved by `weeks`. */
export function weekStart(day: Date, weeks = 0): Date {
  const monday = new Date(day.getFullYear(), day.getMonth(), day.getDate() - ((day.getDay() + 6) % 7) + 7 * weeks)
  return monday
}

/** ISO 8601 week number ("KW 48"). */
export function isoWeek(monday: Date): number {
  const thursday = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + 3)
  const firstThursday = new Date(thursday.getFullYear(), 0, 4)
  return 1 + Math.round((thursday.getTime() - weekStart(firstThursday).getTime() - 3 * 86400000) / (7 * 86400000))
}

/** The seven days from Monday, each with the bookings that start that day, in time order. */
export function byDay(monday: Date, bookings: PlannedBooking[]): { day: Date; items: PlannedBooking[] }[] {
  return Array.from({ length: 7 }, (_, index) => {
    const day = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + index)
    const items = bookings.filter(booking => new Date(booking.startsAt).toDateString() === day.toDateString())
      .sort((a, b) => a.startsAt.localeCompare(b.startsAt) || a.venueName.localeCompare(b.venueName))
    return { day, items }
  })
}

/** One week of every chosen venue: one bookings read per venue (a club has a handful). */
export async function readWeek(venues: Venue[], monday: Date, request: typeof fetch = fetch): Promise<PlannedBooking[] | 'denied'> {
  const next = weekStart(monday, 1)
  const results = await Promise.all(venues.map(venue =>
    readBookings(venue.id, monday.toISOString(), next.toISOString(), request)
      .then(items => items === 'denied' ? 'denied' as const : items.map(item => ({ ...item, venueName: venue.name })))))
  if (results.some(result => result === 'denied')) return 'denied'
  return (results as PlannedBooking[][]).flat()
}
