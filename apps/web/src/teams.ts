import { readItems, sendJson, type Denied } from './api'

export type Team = { id: string; name: string; memberCount: number }

function isTeam(item: unknown): item is Team {
  return item !== null && typeof item === 'object'
    && 'id' in item && typeof item.id === 'string'
    && 'name' in item && typeof item.name === 'string'
    && 'memberCount' in item && typeof item.memberCount === 'number'
}

/** Visible teams; 'denied' for 401/403; throws on failures or a malformed team. */
export async function readTeams(request: typeof fetch = fetch): Promise<Team[] | Denied> {
  const page = await readItems<unknown>('/api/v1/teams', undefined, request)
  if (page === 'denied') return page
  if (!page.items.every(isTeam)) throw new Error('Unexpected response')
  return page.items as Team[]
}

export const createTeam = (name: string, csrfToken: string, request: typeof fetch = fetch) =>
  sendJson('/api/v1/teams', 'POST', csrfToken, { name }, request)

export const renameTeam = (teamId: string, name: string, csrfToken: string, request: typeof fetch = fetch) =>
  sendJson('/api/v1/teams/' + encodeURIComponent(teamId), 'PATCH', csrfToken, { name }, request)

/** Deletion repeats the team name as an explicit confirmation. */
export const deleteTeam = (teamId: string, confirmation: string, csrfToken: string, request: typeof fetch = fetch) =>
  sendJson('/api/v1/teams/' + encodeURIComponent(teamId), 'DELETE', csrfToken, { name: confirmation }, request)

export function rosterPath(teamId: string, cursor?: string): string {
  const args = new URLSearchParams({ limit: '20' })
  if (cursor) args.set('cursor', cursor)
  return '/api/v1/teams/' + encodeURIComponent(teamId) + '/members?' + args
}

export function readRoster(teamId: string, cursor?: string, signal?: AbortSignal, request: typeof fetch = fetch) {
  return readItems<{ id: string; firstName: string; lastName: string }>(rosterPath(teamId, cursor), signal, request)
}

/** Assign (POST) or unassign (DELETE) a member; no body. Returns 'denied' for 401/403; throws otherwise. */
export async function changeAssignment(teamId: string, memberId: string, method: 'POST' | 'DELETE',
  csrfToken: string, request: typeof fetch = fetch): Promise<'ok' | Denied> {
  const response = await request('/api/v1/teams/' + encodeURIComponent(teamId) + '/members/' +
    encodeURIComponent(memberId), { method, credentials: 'same-origin', headers: { 'X-CSRF-Token': csrfToken } })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Assignment rejected')
  return 'ok'
}
