/** Actual attendance and the J+S export (OpenAPI 1.27.0), for a team's managers. */
import type { AttendanceResponse } from './events'

export type PresencePerson = {
  memberId: string; displayName: string; role: 'PARTICIPANT' | 'LEADER'; response: AttendanceResponse | null; present: boolean
}
export type Presence = { open: boolean; items: PresencePerson[] }
export type JsCheck = { from: string; to: string; rows: number; withoutNumber: string[]; trainingsWithoutPlace: string[] }

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
const texts = (value: unknown) => Array.isArray(value) && value.every(item => typeof item === 'string')

function isPerson(value: unknown): value is PresencePerson {
  return record(value) && Object.keys(value).length === 5 && typeof value.memberId === 'string' &&
    typeof value.displayName === 'string' && (value.role === 'PARTICIPANT' || value.role === 'LEADER') &&
    (value.response === null || value.response === 'YES' || value.response === 'NO' || value.response === 'MAYBE') &&
    typeof value.present === 'boolean'
}

export async function readPresence(eventId: string, request: typeof fetch = fetch): Promise<Presence | 'denied'> {
  const response = await request('/api/v1/events/' + encodeURIComponent(eventId) + '/presence',
    { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403 || response.status === 404) return 'denied'
  if (!response.ok) throw new Error('Presence unavailable')
  const body: unknown = await response.json()
  if (!record(body) || Object.keys(body).length !== 2 || typeof body.open !== 'boolean' ||
    !Array.isArray(body.items) || !body.items.every(isPerson)) throw new Error('Invalid presence')
  return body as Presence
}

export type SaveOutcome = 'ok' | 'not_started' | 'denied' | 'invalid'

export async function savePresence(eventId: string, present: string[], csrfToken: string,
  request: typeof fetch = fetch): Promise<SaveOutcome> {
  const response = await request('/api/v1/events/' + encodeURIComponent(eventId) + '/presence', {
    method: 'PUT', credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken, 'Content-Type': 'application/json' }, body: JSON.stringify({ present }),
  })
  if (response.ok) return 'ok'
  if (response.status === 409) return 'not_started'
  if (response.status === 400) return 'invalid'
  if (response.status === 401 || response.status === 403 || response.status === 404) return 'denied'
  throw new Error('Saving failed')
}

const query = (from: string, to: string) => {
  const value = new URLSearchParams({ ...(from ? { from } : {}), ...(to ? { to } : {}) }).toString()
  return value ? '?' + value : ''
}

/** The address of the NDS import file for the same range as [checkJs]. */
export const jsFileHref = (teamId: string, from: string, to: string) =>
  '/api/v1/teams/' + encodeURIComponent(teamId) + '/js-attendance.csv' + query(from, to)

export async function checkJs(teamId: string, from: string, to: string,
  request: typeof fetch = fetch): Promise<JsCheck | 'denied' | 'invalid'> {
  const response = await request('/api/v1/teams/' + encodeURIComponent(teamId) + '/js-attendance' + query(from, to),
    { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403 || response.status === 404) return 'denied'
  if (response.status === 400) return 'invalid'
  if (!response.ok) throw new Error('Check unavailable')
  const body: unknown = await response.json()
  if (!record(body) || Object.keys(body).length !== 5 || typeof body.from !== 'string' || typeof body.to !== 'string' ||
    typeof body.rows !== 'number' || !texts(body.withoutNumber) || !texts(body.trainingsWithoutPlace)) throw new Error('Invalid check')
  return body as JsCheck
}
