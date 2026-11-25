/** Trainings and events (OpenAPI 1.10.0). */
export type EventKind = 'TRAINING' | 'MATCH' | 'OTHER'

export type AttendanceResponse = 'YES' | 'NO' | 'MAYBE'
export type Counts = { yes: number; no: number; maybe: number }
export type TeamEvent = {
  id: string; teamId: string; teamName: string; kind: EventKind; title: string
  startsAt: string; endsAt: string; location: string | null; notes: string | null; cancelled: boolean; counts: Counts
}
/** A person's own squad status for a match with a saved squad (OpenAPI 1.28.0); meeting details only when called. */
export type OwnSquad = { called: boolean; meetAt: string | null; meetingPoint: string | null }
export type MyEvent = TeamEvent & { for: { id: string; firstName: string; response: AttendanceResponse | null; squad: OwnSquad | null }[] }

export type EventInput = {
  kind: EventKind; title: string; startsAt: string; endsAt: string; location: string; notes: string
}

const eventKeys = ['id', 'teamId', 'teamName', 'kind', 'title', 'startsAt', 'endsAt', 'location', 'notes', 'cancelled', 'counts']
const text = (value: unknown) => typeof value === 'string'
const textOrNull = (value: unknown) => value === null || typeof value === 'string'
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
const count = (value: unknown) => typeof value === 'number' && Number.isInteger(value) && value >= 0
const isResponse = (value: unknown) => value === null || value === 'YES' || value === 'NO' || value === 'MAYBE'
function isCounts(value: unknown): value is Counts {
  return record(value) && Object.keys(value).length === 3 && count(value.yes) && count(value.no) && count(value.maybe)
}

function isEvent(value: unknown, extra: string[]): value is TeamEvent {
  if (!record(value)) return false
  const keys = [...eventKeys, ...extra]
  return Object.keys(value).length === keys.length && keys.every(key => key in value) &&
    text(value.id) && text(value.teamId) && text(value.teamName) &&
    (value.kind === 'TRAINING' || value.kind === 'MATCH' || value.kind === 'OTHER') && text(value.title) &&
    text(value.startsAt) && !Number.isNaN(Date.parse(value.startsAt as string)) &&
    text(value.endsAt) && !Number.isNaN(Date.parse(value.endsAt as string)) &&
    textOrNull(value.location) && textOrNull(value.notes) && typeof value.cancelled === 'boolean' && isCounts(value.counts)
}

function isMyEvent(value: unknown): value is MyEvent {
  return isEvent(value, ['for']) && Array.isArray((value as MyEvent).for) && (value as MyEvent).for.every(person =>
    record(person) && Object.keys(person).length === 4 && text(person.id) && text(person.firstName) &&
    isResponse(person.response) && (person.squad === null || (record(person.squad) && Object.keys(person.squad).length === 3 &&
      typeof person.squad.called === 'boolean' && textOrNull(person.squad.meetAt) && textOrNull(person.squad.meetingPoint))))
}

async function readJson(path: string, request: typeof fetch): Promise<Record<string, unknown> | 'denied'> {
  const response = await request(path, { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Events unavailable')
  const body: unknown = await response.json()
  if (!record(body) || !Array.isArray(body.items) || typeof body.truncated !== 'boolean') throw new Error('Invalid events')
  return body
}

/** The next eight weeks of the signed-in member's own and their children's events. */
export async function readMyEvents(request: typeof fetch = fetch): Promise<{ items: MyEvent[]; truncated: boolean } | 'denied'> {
  const body = await readJson('/api/v1/me/events', request)
  if (body === 'denied') return body
  if (!(body.items as unknown[]).every(isMyEvent)) throw new Error('Invalid events')
  return { items: body.items as MyEvent[], truncated: body.truncated as boolean }
}

/** The next eight weeks of one team's events, and whether the caller may change them. */
export async function readTeamEvents(teamId: string, request: typeof fetch = fetch):
  Promise<{ items: TeamEvent[]; truncated: boolean; canManage: boolean } | 'denied'> {
  const body = await readJson('/api/v1/teams/' + encodeURIComponent(teamId) + '/events', request)
  if (body === 'denied') return body
  if (!(body.items as unknown[]).every(item => isEvent(item, [])) || typeof body.canManage !== 'boolean') {
    throw new Error('Invalid events')
  }
  return { items: body.items as TeamEvent[], truncated: body.truncated as boolean, canManage: body.canManage }
}

export type EventOutcome = 'ok' | 'invalid' | 'denied' | 'not-found'

async function write(path: string, method: 'POST' | 'PUT' | 'DELETE', csrfToken: string, body: object | undefined,
  request: typeof fetch): Promise<EventOutcome> {
  const response = await request(path, {
    method, credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  if (response.ok) return 'ok'
  if (response.status === 400) return 'invalid'
  if (response.status === 401 || response.status === 403) return 'denied'
  if (response.status === 404) return 'not-found'
  throw new Error('Request failed')
}

function eventBody(input: EventInput) {
  return {
    kind: input.kind, title: input.title.trim(), startsAt: input.startsAt, endsAt: input.endsAt,
    ...(input.location.trim() ? { location: input.location.trim() } : {}),
    ...(input.notes.trim() ? { notes: input.notes.trim() } : {}),
  }
}

/** Creates the event and, with [repeatWeeklyUntil], its weekly copies in the browser's time zone. */
export function createEvents(teamId: string, input: EventInput, repeatWeeklyUntil: string, csrfToken: string,
  request: typeof fetch = fetch, timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone) {
  return write('/api/v1/teams/' + encodeURIComponent(teamId) + '/events', 'POST', csrfToken,
    { ...eventBody(input), ...(repeatWeeklyUntil ? { repeatWeeklyUntil, timeZone } : {}) }, request)
}

export function replaceEvent(eventId: string, input: EventInput, cancelled: boolean, csrfToken: string,
  request: typeof fetch = fetch) {
  return write('/api/v1/events/' + encodeURIComponent(eventId), 'PUT', csrfToken, { ...eventBody(input), cancelled }, request)
}

export function deleteEvent(eventId: string, csrfToken: string, request: typeof fetch = fetch) {
  return write('/api/v1/events/' + encodeURIComponent(eventId), 'DELETE', csrfToken, undefined, request)
}

const pad = (value: number) => String(value).padStart(2, '0')

/** The local date and times of a form as absolute instants; null when the end is not after the start. */
export function instants(date: string, start: string, end: string): { startsAt: string; endsAt: string } | null {
  const from = new Date(`${date}T${start}`)
  const to = new Date(`${date}T${end}`)
  if (Number.isNaN(from.getTime()) || Number.isNaN(to.getTime()) || to <= from) return null
  return { startsAt: from.toISOString(), endsAt: to.toISOString() }
}

/** An event's local date and times for the edit form. */
export function localParts(event: Pick<TeamEvent, 'startsAt' | 'endsAt'>) {
  const from = new Date(event.startsAt)
  const to = new Date(event.endsAt)
  return {
    date: `${from.getFullYear()}-${pad(from.getMonth() + 1)}-${pad(from.getDate())}`,
    start: `${pad(from.getHours())}:${pad(from.getMinutes())}`,
    end: `${pad(to.getHours())}:${pad(to.getMinutes())}`,
  }
}

const locales = { de: 'de-CH', en: 'en-GB', fr: 'fr-CH', it: 'it-CH' } as const

/** "Thu 26 Nov, 18:00–19:30" in the chosen language and the browser's time zone. */
export function formatWhen(event: Pick<TeamEvent, 'startsAt' | 'endsAt'>, language: keyof typeof locales): string {
  const locale = locales[language]
  const from = new Date(event.startsAt)
  const to = new Date(event.endsAt)
  const day = new Intl.DateTimeFormat(locale, { weekday: 'short', day: 'numeric', month: 'short' }).format(from)
  const time = new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit' })
  const sameDay = from.toDateString() === to.toDateString()
  const end = sameDay ? time.format(to) : new Intl.DateTimeFormat(locale,
    { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }).format(to)
  return `${day}, ${time.format(from)}–${end}`
}

/** The caller may answer until the event starts, and not for a cancelled event (the server decides; 409 otherwise). */
export const answerable = (event: Pick<TeamEvent, 'startsAt' | 'cancelled'>, now = Date.now()) =>
  !event.cancelled && Date.parse(event.startsAt) > now

const attendancePath = (eventId: string, memberId: string) =>
  '/api/v1/events/' + encodeURIComponent(eventId) + '/attendance/' + encodeURIComponent(memberId)

export type AttendanceOutcome = 'ok' | 'closed' | 'denied'

async function attendanceWrite(path: string, method: 'PUT' | 'DELETE', csrfToken: string, body: object | undefined,
  request: typeof fetch): Promise<AttendanceOutcome> {
  const response = await request(path, {
    method, credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  if (response.ok) return 'ok'
  if (response.status === 409) return 'closed'
  if (response.status === 401 || response.status === 403) return 'denied'
  throw new Error('Request failed')
}

/** Answers for the member themselves or for one's child (OpenAPI 1.11.0). */
export function answerAttendance(eventId: string, memberId: string, response: AttendanceResponse, csrfToken: string,
  request: typeof fetch = fetch) {
  return attendanceWrite(attendancePath(eventId, memberId), 'PUT', csrfToken, { response }, request)
}

export function clearAttendance(eventId: string, memberId: string, csrfToken: string, request: typeof fetch = fetch) {
  return attendanceWrite(attendancePath(eventId, memberId), 'DELETE', csrfToken, undefined, request)
}

export type AttendanceList = { items: { displayName: string; response: AttendanceResponse | null }[]; counts: Counts }

/** Every team member's answer for one event, for the team's viewers and managers. */
export async function readAttendance(eventId: string, request: typeof fetch = fetch): Promise<AttendanceList | 'denied'> {
  const response = await request('/api/v1/events/' + encodeURIComponent(eventId) + '/attendance',
    { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Attendance unavailable')
  const body: unknown = await response.json()
  if (!record(body) || Object.keys(body).length !== 2 || !Array.isArray(body.items) || !isCounts(body.counts) ||
    !body.items.every(item => record(item) && Object.keys(item).length === 2 && text(item.displayName) && isResponse(item.response))) {
    throw new Error('Invalid attendance')
  }
  return body as AttendanceList
}

export type AttendanceTally = { yes: number; no: number; maybe: number; noAnswer: number }
export type AttendanceStatistics = {
  from: string; to: string; held: number
  items: (AttendanceTally & { displayName: string })[]
  totals: AttendanceTally
}

const tally = (value: unknown, extra: string[] = []): boolean => record(value) &&
  Object.keys(value).length === 4 + extra.length && ['yes', 'no', 'maybe', 'noAnswer'].every(key => count(value[key]))

/** How each team member answered the team's past events (OpenAPI 1.18.0); dates are YYYY-MM-DD (UTC), both optional. */
export async function readAttendanceStatistics(teamId: string, from?: string, to?: string,
  request: typeof fetch = fetch): Promise<AttendanceStatistics | 'denied' | 'invalid'> {
  const query = new URLSearchParams({ ...(from ? { from } : {}), ...(to ? { to } : {}) }).toString()
  const response = await request('/api/v1/teams/' + encodeURIComponent(teamId) + '/attendance-statistics' +
    (query ? '?' + query : ''), { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403 || response.status === 404) return 'denied'
  if (response.status === 400) return 'invalid'
  if (!response.ok) throw new Error('Statistics unavailable')
  const body: unknown = await response.json()
  if (!record(body) || Object.keys(body).length !== 5 || !text(body.from) || !text(body.to) || !count(body.held) ||
    !tally(body.totals) || !Array.isArray(body.items) ||
    !body.items.every(item => tally(item, ['displayName']) && text((item as Record<string, unknown>).displayName))) {
    throw new Error('Invalid statistics')
  }
  return body as AttendanceStatistics
}

/** A coach reminds the team members without an answer (OpenAPI 1.25.0). */
export type ReminderOutcome = { reminded: number } | 'closed' | 'email_off' | 'recent' | 'denied'

export async function remindMissing(eventId: string, csrfToken: string, request: typeof fetch = fetch): Promise<ReminderOutcome> {
  const response = await request('/api/v1/events/' + encodeURIComponent(eventId) + '/attendance-reminders', {
    method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-Token': csrfToken },
  })
  if (response.status === 401 || response.status === 403 || response.status === 404) return 'denied'
  if (response.status === 409) {
    const code = await response.json().then(value => (record(value) ? value.code : null), () => null)
    return code === 'email_off' || code === 'recent' ? code : 'closed'
  }
  if (!response.ok) throw new Error('Reminder failed')
  const body: unknown = await response.json()
  if (!record(body) || Object.keys(body).length !== 1 || typeof body.reminded !== 'number' || !Number.isInteger(body.reminded) || body.reminded < 0) {
    throw new Error('Invalid reminder answer')
  }
  return { reminded: body.reminded }
}
