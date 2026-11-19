/** Announcements (OpenAPI 1.14.0). Plain text only: rendered as text, never as HTML. */
export type Announcement = {
  id: string; teamId: string | null; teamName: string | null; title: string; body: string
  showUntil: string; createdAt: string; canManage: boolean
}
export type Audience = { teamId: string | null; teamName: string | null }

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
const text = (value: unknown) => typeof value === 'string'
const textOrNull = (value: unknown) => value === null || typeof value === 'string'
const exactly = (value: Record<string, unknown>, keys: string[]) =>
  Object.keys(value).length === keys.length && keys.every(key => key in value)

function isAnnouncement(value: unknown): value is Announcement {
  return record(value) && exactly(value, ['id', 'teamId', 'teamName', 'title', 'body', 'showUntil', 'createdAt', 'canManage']) &&
    text(value.id) && textOrNull(value.teamId) && textOrNull(value.teamName) && text(value.title) && text(value.body) &&
    text(value.showUntil) && text(value.createdAt) && typeof value.canManage === 'boolean'
}

function isAudience(value: unknown): value is Audience {
  return record(value) && exactly(value, ['teamId', 'teamName']) && textOrNull(value.teamId) && textOrNull(value.teamName)
}

export async function readAnnouncements(request: typeof fetch = fetch):
  Promise<{ items: Announcement[]; audiences: Audience[] } | 'denied'> {
  const response = await request('/api/v1/me/announcements', { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Announcements unavailable')
  const body: unknown = await response.json()
  if (!record(body) || !exactly(body, ['items', 'audiences']) || !Array.isArray(body.items) || !Array.isArray(body.audiences) ||
    !body.items.every(isAnnouncement) || !body.audiences.every(isAudience)) {
    throw new Error('Invalid announcements')
  }
  return { items: body.items as Announcement[], audiences: body.audiences as Audience[] }
}

export type AnnouncementOutcome = 'ok' | 'invalid' | 'denied' | 'not-found'

async function write(path: string, method: 'POST' | 'PUT' | 'DELETE', csrfToken: string, body: object | undefined,
  request: typeof fetch): Promise<AnnouncementOutcome> {
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

export type Draft = { title: string; body: string; showUntil: string }
const content = (draft: Draft) => ({ title: draft.title.trim(), body: draft.body.trim(), ...(draft.showUntil ? { showUntil: draft.showUntil } : {}) })

export const postAnnouncement = (teamId: string | null, draft: Draft, csrfToken: string, request: typeof fetch = fetch) =>
  write('/api/v1/announcements', 'POST', csrfToken, { teamId, ...content(draft) }, request)

export const replaceAnnouncement = (id: string, draft: Draft, csrfToken: string, request: typeof fetch = fetch) =>
  write('/api/v1/announcements/' + encodeURIComponent(id), 'PUT', csrfToken, content(draft), request)

export const deleteAnnouncement = (id: string, csrfToken: string, request: typeof fetch = fetch) =>
  write('/api/v1/announcements/' + encodeURIComponent(id), 'DELETE', csrfToken, undefined, request)
