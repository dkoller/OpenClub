/** Trainings and events (OpenAPI 1.10.0). */
export type EventKind = 'TRAINING' | 'MATCH' | 'OTHER'

export type TeamEvent = {
  id: string; teamId: string; teamName: string; kind: EventKind; title: string
  startsAt: string; endsAt: string; location: string | null; notes: string | null; cancelled: boolean
}
export type MyEvent = TeamEvent & { for: { id: string; firstName: string }[] }

export type EventInput = {
  kind: EventKind; title: string; startsAt: string; endsAt: string; location: string; notes: string
}

const eventKeys = ['id', 'teamId', 'teamName', 'kind', 'title', 'startsAt', 'endsAt', 'location', 'notes', 'cancelled']
const text = (value: unknown) => typeof value === 'string'
const textOrNull = (value: unknown) => value === null || typeof value === 'string'
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

function isEvent(value: unknown, extra: string[]): value is TeamEvent {
  if (!record(value)) return false
  const keys = [...eventKeys, ...extra]
  return Object.keys(value).length === keys.length && keys.every(key => key in value) &&
    text(value.id) && text(value.teamId) && text(value.teamName) &&
    (value.kind === 'TRAINING' || value.kind === 'MATCH' || value.kind === 'OTHER') && text(value.title) &&
    text(value.startsAt) && !Number.isNaN(Date.parse(value.startsAt as string)) &&
    text(value.endsAt) && !Number.isNaN(Date.parse(value.endsAt as string)) &&
    textOrNull(value.location) && textOrNull(value.notes) && typeof value.cancelled === 'boolean'
}

function isMyEvent(value: unknown): value is MyEvent {
  return isEvent(value, ['for']) && Array.isArray((value as MyEvent).for) && (value as MyEvent).for.every(person =>
    record(person) && Object.keys(person).length === 2 && text(person.id) && text(person.firstName))
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
