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
