import { readItems, writeJson, type Denied } from './api'

export type Role = 'ADMIN' | 'MEMBER'

export function rolePath(memberId: string): string {
  return '/api/v1/members/' + encodeURIComponent(memberId) + '/role'
}

/** Reads one member's role. Returns 'denied' for 401/403; throws on any other failure or an unknown role. */
export async function readRole(memberId: string, signal?: AbortSignal,
  request: typeof fetch = fetch): Promise<Role | Denied> {
  const response = await request(rolePath(memberId),
    { credentials: 'same-origin', cache: 'no-store', ...(signal ? { signal } : {}) })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Role unavailable')
  const value: unknown = await response.json()
  if (!value || typeof value !== 'object' || !('role' in value) ||
      (value.role !== 'ADMIN' && value.role !== 'MEMBER')) throw new Error('Invalid role')
  return value.role
}

export type RoleHolder = { memberId: string; firstName: string; lastName: string; role: Role }

/** Everyone with a role above member (OpenAPI 1.38.0), for MANAGE_PERMISSIONS holders. */
export function fetchRoleHolders(signal?: AbortSignal, request: typeof fetch = fetch) {
  return readItems<RoleHolder>('/api/v1/roles/holders', signal, request)
}

export function saveRole(memberId: string, role: Role, csrfToken: string, request: typeof fetch = fetch) {
  return writeJson(rolePath(memberId), 'PATCH', csrfToken, { role }, request)
}

/**
 * Hands out one AbortSignal per lookup and aborts the previous one, so a slower,
 * superseded member search or role read can never overwrite the latest selection.
 */
export function latestLookup() {
  let current: AbortController | null = null
  return {
    next(): AbortSignal {
      current?.abort()
      current = new AbortController()
      return current.signal
    },
    cancel() { current?.abort() },
  }
}
