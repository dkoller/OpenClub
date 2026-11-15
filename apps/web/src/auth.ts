export type ClubAccess =
  | { state: 'signed-out' }
  | { state: 'not-member'; csrfToken: string }
  | { state: 'ready'; csrfToken: string }
  | { state: 'unavailable' }

export async function loadClubAccess(request: typeof fetch = fetch): Promise<ClubAccess> {
  try {
    const session = await request('/api/v1/auth/me', { credentials: 'same-origin', cache: 'no-store' })
    if (session.status === 401) return { state: 'signed-out' }
    if (!session.ok) return { state: 'unavailable' }
    const details: unknown = await session.json()
    if (!isSession(details)) return { state: 'unavailable' }

    const club = await request('/api/v1/club', { credentials: 'same-origin', cache: 'no-store' })
    if (club.status === 401) return { state: 'signed-out' }
    if (club.status === 403) return { state: 'not-member', csrfToken: details.csrfToken }
    if (club.status !== 200) return { state: 'unavailable' }
    const clubDetails: unknown = await club.json()
    if (!isClub(clubDetails)) return { state: 'unavailable' }
    return { state: 'ready', csrfToken: details.csrfToken }
  } catch {
    return { state: 'unavailable' }
  }
}

function isSession(value: unknown): value is { accountId: string; csrfToken: string } {
  return value !== null && typeof value === 'object'
    && 'accountId' in value && typeof value.accountId === 'string'
    && isUuid(value.accountId)
    && 'csrfToken' in value && typeof value.csrfToken === 'string'
    && /^[A-Za-z0-9_-]{43}$/.test(value.csrfToken)
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)
}

function isClub(value: unknown): value is { id: string } {
  return value !== null && typeof value === 'object'
    && 'id' in value && typeof value.id === 'string'
    && isUuid(value.id)
}

export async function signOut(csrfToken: string, request: typeof fetch = fetch): Promise<boolean> {
  try {
    const result = await request('/api/v1/auth/logout', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
      body: '{}',
    })
    return result.status === 204 || result.status === 401
  } catch {
    return false
  }
}
