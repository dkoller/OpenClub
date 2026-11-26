/** Match squads (OpenAPI 1.28.0), for a team's managers. */
import type { AttendanceResponse } from './events'

export type SquadPerson = { memberId: string; displayName: string; response: AttendanceResponse | null; called: boolean }
export type Squad = { open: boolean; meetAt: string | null; meetingPoint: string | null; items: SquadPerson[] }

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
const textOrNull = (value: unknown) => value === null || typeof value === 'string'

function isPerson(value: unknown): value is SquadPerson {
  return record(value) && Object.keys(value).length === 4 && typeof value.memberId === 'string' &&
    typeof value.displayName === 'string' && typeof value.called === 'boolean' &&
    (value.response === null || value.response === 'YES' || value.response === 'NO' || value.response === 'MAYBE')
}

const path = (eventId: string) => '/api/v1/events/' + encodeURIComponent(eventId) + '/squad'

export async function readSquad(eventId: string, request: typeof fetch = fetch): Promise<Squad | 'denied'> {
  const response = await request(path(eventId), { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403 || response.status === 404 || response.status === 409) return 'denied'
  if (!response.ok) throw new Error('Squad unavailable')
  const body: unknown = await response.json()
  if (!record(body) || Object.keys(body).length !== 4 || typeof body.open !== 'boolean' || !textOrNull(body.meetAt) ||
    !textOrNull(body.meetingPoint) || !Array.isArray(body.items) || !body.items.every(isPerson)) throw new Error('Invalid squad')
  return body as Squad
}

export type SquadOutcome = 'ok' | 'closed' | 'invalid' | 'denied'

export async function saveSquad(eventId: string, memberIds: string[], meetAt: string | null, meetingPoint: string | null,
  csrfToken: string, request: typeof fetch = fetch): Promise<SquadOutcome> {
  const response = await request(path(eventId), {
    method: 'PUT', credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken, 'Content-Type': 'application/json' },
    body: JSON.stringify({ memberIds, meetAt, meetingPoint }),
  })
  if (response.ok) return 'ok'
  if (response.status === 409) return 'closed'
  if (response.status === 400) return 'invalid'
  if (response.status === 401 || response.status === 403 || response.status === 404) return 'denied'
  throw new Error('Saving failed')
}

const pad = (n: number) => String(n).padStart(2, '0')
/** The meeting time as "HH:mm" for a time field: the meeting is on the match day, so only the time is asked. */
export const toTime = (instant: string) => { const date = new Date(instant); return `${pad(date.getHours())}:${pad(date.getMinutes())}` }
/** "HH:mm" on the match's day, back to an instant; a time after kick-off means the evening before. */
export function meetInstant(time: string, startsAt: string): string | null {
  const match = /^(\d{2}):(\d{2})$/.exec(time)
  if (!match) return null
  const start = new Date(startsAt)
  const meet = new Date(start.getFullYear(), start.getMonth(), start.getDate(), Number(match[1]), Number(match[2]))
  if (meet.getTime() > start.getTime()) meet.setDate(meet.getDate() - 1)
  return meet.toISOString()
}
