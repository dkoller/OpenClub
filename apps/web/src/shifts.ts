/** Volunteer shifts of a team event (OpenAPI 1.16.0). */
export type Shift = {
  id: string; title: string; startsAt: string; endsAt: string; needed: number
  /** Public display names, in sign-up order. */
  volunteers: string[]
  /** Which of the caller's people are signed up. */
  signedUp: string[]
}
export type ShiftList = { items: Shift[]; people: { id: string; firstName: string }[]; canManage: boolean }
export type ShiftInput = { title: string; startsAt: string; endsAt: string; needed: number }

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
const text = (value: unknown): value is string => typeof value === 'string'
const exactly = (value: Record<string, unknown>, keys: string[]) =>
  Object.keys(value).length === keys.length && keys.every(key => key in value)
const texts = (value: unknown) => Array.isArray(value) && value.every(text)

/** Accepts exactly the documented shape, so an unexpected field is never shown. */
export function parseShiftList(body: unknown): ShiftList {
  const valid = record(body) && exactly(body, ['items', 'people', 'canManage']) && typeof body.canManage === 'boolean' &&
    Array.isArray(body.people) && body.people.every(person => record(person) && exactly(person, ['id', 'firstName']) &&
      text(person.id) && text(person.firstName)) &&
    Array.isArray(body.items) && body.items.every(item => record(item) &&
      exactly(item, ['id', 'title', 'startsAt', 'endsAt', 'needed', 'volunteers', 'signedUp']) &&
      text(item.id) && text(item.title) && text(item.startsAt) && text(item.endsAt) &&
      typeof item.needed === 'number' && Number.isInteger(item.needed) && item.needed > 0 &&
      texts(item.volunteers) && texts(item.signedUp))
  if (!valid) throw new Error('Invalid shifts')
  return body as ShiftList
}

const shiftsPath = (eventId: string) => '/api/v1/events/' + encodeURIComponent(eventId) + '/shifts'
const shiftPath = (shiftId: string) => '/api/v1/shifts/' + encodeURIComponent(shiftId)

export async function readShifts(eventId: string, request: typeof fetch = fetch): Promise<ShiftList | 'denied'> {
  const response = await request(shiftsPath(eventId), { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403 || response.status === 404) return 'denied'
  if (!response.ok) throw new Error('Shifts unavailable')
  return parseShiftList(await response.json())
}

/** 'closed': started or cancelled; 'full': no free place; 'conflict': fewer places than volunteers. */
export type ShiftOutcome = 'ok' | 'invalid' | 'denied' | 'closed' | 'full' | 'conflict'

async function write(path: string, method: 'POST' | 'PUT' | 'DELETE', csrfToken: string, body: object | undefined,
  request: typeof fetch): Promise<ShiftOutcome> {
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
    return code === 'full' ? 'full' : code === 'conflict' ? 'conflict' : 'closed'
  }
  throw new Error('Request failed')
}

export function createShift(eventId: string, input: ShiftInput, csrfToken: string, request: typeof fetch = fetch) {
  return write(shiftsPath(eventId), 'POST', csrfToken, input, request)
}
export function replaceShift(shiftId: string, input: ShiftInput, csrfToken: string, request: typeof fetch = fetch) {
  return write(shiftPath(shiftId), 'PUT', csrfToken, input, request)
}
export function deleteShift(shiftId: string, csrfToken: string, request: typeof fetch = fetch) {
  return write(shiftPath(shiftId), 'DELETE', csrfToken, undefined, request)
}
/** Signs up (or, with [withdraw], withdraws) the caller or their child. */
export function volunteer(shiftId: string, memberId: string, withdraw: boolean, csrfToken: string, request: typeof fetch = fetch) {
  return write(shiftPath(shiftId) + '/volunteers/' + encodeURIComponent(memberId), withdraw ? 'DELETE' : 'PUT',
    csrfToken, undefined, request)
}

/** A shift of the next 14 days that still needs people, for the managers' overview (OpenAPI 1.23.0). Counts only. */
export type UnfilledShift = {
  id: string; title: string; startsAt: string; endsAt: string; needed: number; filled: number
  eventId: string; eventTitle: string; teamId: string; teamName: string
}
export type UnfilledList = { items: UnfilledShift[]; truncated: boolean }

const count = (value: unknown): value is number => typeof value === 'number' && Number.isInteger(value) && value >= 0

export function parseUnfilled(body: unknown): UnfilledList {
  const valid = record(body) && exactly(body, ['items', 'truncated']) && typeof body.truncated === 'boolean' &&
    Array.isArray(body.items) && body.items.every(item => record(item) &&
      exactly(item, ['id', 'title', 'startsAt', 'endsAt', 'needed', 'filled', 'eventId', 'eventTitle', 'teamId', 'teamName']) &&
      text(item.id) && text(item.title) && text(item.startsAt) && text(item.endsAt) && text(item.eventId) &&
      text(item.eventTitle) && text(item.teamId) && text(item.teamName) &&
      count(item.needed) && item.needed > 0 && count(item.filled) && item.filled < item.needed)
  if (!valid) throw new Error('Invalid unfilled shifts')
  return body as UnfilledList
}

/** 'denied' for anyone who manages no team. */
export async function readUnfilled(request: typeof fetch = fetch): Promise<UnfilledList | 'denied'> {
  const response = await request('/api/v1/shifts/unfilled', { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Unfilled shifts unavailable')
  return parseUnfilled(await response.json())
}
