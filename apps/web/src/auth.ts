export type ClubAccess =
  | { state: 'signed-out' }
  | { state: 'not-member' }
  | { state: 'ready'; csrfToken: string }
  | { state: 'unavailable' }

export async function loadClubAccess(request: typeof fetch = fetch): Promise<ClubAccess> {
  try {
    const session = await request('/api/v1/auth/me', { credentials: 'same-origin' })
    if (session.status === 401) return { state: 'signed-out' }
    if (!session.ok) return { state: 'unavailable' }
    const details: unknown = await session.json()
    if (!isSession(details)) return { state: 'unavailable' }

    const club = await request('/api/v1/club', { credentials: 'same-origin' })
    if (club.status === 403) return { state: 'not-member' }
    if (!club.ok) return { state: 'unavailable' }
    return { state: 'ready', csrfToken: details.csrfToken }
  } catch {
    return { state: 'unavailable' }
  }
}

function isSession(value: unknown): value is { accountId: string; csrfToken: string } {
  return value !== null && typeof value === 'object'
    && 'accountId' in value && typeof value.accountId === 'string'
    && 'csrfToken' in value && typeof value.csrfToken === 'string'
}

export async function signOut(csrfToken: string, request: typeof fetch = fetch): Promise<boolean> {
  try {
    const result = await request('/api/v1/auth/logout', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
      body: '{}',
    })
    return result.status === 204
  } catch {
    return false
  }
}
