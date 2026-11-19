import { readItems, type Denied } from './api'
import type { Member } from './members'

/** A guardian's view of one of their minors (OpenAPI 1.6.0); exactly these fields, nothing else. */
export type Dependent = {
  id: string; firstName: string; lastName: string; dateOfBirth: string
  email: string | null; phone: string | null; isPlayer: boolean; teams: string[]
}

const base = (minorId: string) => '/api/v1/members/' + encodeURIComponent(minorId) + '/guardians'
const dependentPath = (minorId: string) => '/api/v1/me/dependents/' + encodeURIComponent(minorId)

async function write(path: string, method: 'POST' | 'DELETE' | 'PUT', csrfToken: string, body: object | undefined,
  request: typeof fetch): Promise<'ok' | Denied | 'invalid' | 'not-found' | 'conflict'> {
  const response = await request(path, {
    method, credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  if (response.ok) return 'ok'
  if (response.status === 401 || response.status === 403) return 'denied'
  if (response.status === 400) return 'invalid'
  if (response.status === 404) return 'not-found'
  if (response.status === 409) return 'conflict'
  throw new Error('Request failed')
}

/** Editors: a minor's current guardians. 'not-found' means unknown or not a minor. */
export async function listGuardians(minorId: string, request: typeof fetch = fetch): Promise<Member[] | Denied | 'not-found'> {
  const response = await request(base(minorId), { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 404) return 'not-found'
  const page = await readItems<Member>(base(minorId), undefined, async () => response)
  return page === 'denied' ? page : page.items
}

export const addGuardian = (minorId: string, guardianId: string, csrfToken: string, request: typeof fetch = fetch) =>
  write(base(minorId), 'POST', csrfToken, { guardianMemberId: guardianId }, request)

export const removeGuardian = (minorId: string, guardianId: string, csrfToken: string, request: typeof fetch = fetch) =>
  write(base(minorId) + '/' + encodeURIComponent(guardianId), 'DELETE', csrfToken, undefined, request)

/** The signed-in guardian's minors; everyone else gets an empty list. */
export async function listDependents(signal?: AbortSignal, request: typeof fetch = fetch): Promise<Member[] | Denied> {
  const page = await readItems<Member>('/api/v1/me/dependents', signal, request)
  return page === 'denied' ? page : page.items
}

const dependentKeys = ['id', 'firstName', 'lastName', 'dateOfBirth', 'email', 'phone', 'isPlayer', 'teams']

export async function readDependent(minorId: string, request: typeof fetch = fetch): Promise<Dependent | Denied> {
  const response = await request(dependentPath(minorId), { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Request failed')
  const value: unknown = await response.json()
  const body = value as Record<string, unknown>
  if (!value || typeof value !== 'object' || Object.keys(body).sort().join() !== [...dependentKeys].sort().join() ||
      typeof body.id !== 'string' || typeof body.firstName !== 'string' || typeof body.lastName !== 'string' ||
      typeof body.dateOfBirth !== 'string' || typeof body.isPlayer !== 'boolean' ||
      (body.email !== null && typeof body.email !== 'string') || (body.phone !== null && typeof body.phone !== 'string') ||
      !Array.isArray(body.teams) || !body.teams.every(team => typeof team === 'string')) {
    throw new Error('Invalid dependent')
  }
  return body as Dependent
}

/** Replaces email and phone; an empty field is cleared (the form starts from [readDependent]). */
export function replaceDependentContacts(minorId: string, email: string, phone: string, csrfToken: string,
  request: typeof fetch = fetch) {
  return write(dependentPath(minorId) + '/contacts', 'PUT', csrfToken,
    { ...(email.trim() ? { email: email.trim() } : {}), ...(phone.trim() ? { phone: phone.trim() } : {}) }, request)
}
