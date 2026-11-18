import { sendJson, type Denied } from './api'

export type Invitation = { secret: string; expiresAt: string }

export function invitationPath(memberId: string): string {
  return '/api/v1/members/' + encodeURIComponent(memberId) + '/invitation'
}

export function accountLinkPath(memberId: string): string {
  return '/api/v1/members/' + encodeURIComponent(memberId) + '/account-link'
}

/** The link an administrator hands over. The secret travels in the fragment, so it is never sent to a server. */
export function invitationLink(origin: string, secret: string): string {
  return origin + '/#invite=' + secret
}

/** Reads a secret from a location hash such as "#invite=abc"; anything else is null. */
export function secretFromHash(hash: string): string | null {
  const match = /^#invite=([A-Za-z0-9_-]{43})$/.exec(hash)
  return match ? match[1]! : null
}

/** Creates an invitation. 'conflict' means one is already open or the member cannot be invited. */
export async function createInvitation(memberId: string, csrfToken: string,
  request: typeof fetch = fetch): Promise<Invitation | Denied | 'conflict'> {
  const response = await request(invitationPath(memberId), {
    method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-Token': csrfToken },
  })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (response.status === 409) return 'conflict'
  if (!response.ok) throw new Error('Invitation failed')
  const value: unknown = await response.json()
  if (!value || typeof value !== 'object' || !('secret' in value) || typeof value.secret !== 'string' ||
      !('expiresAt' in value) || typeof value.expiresAt !== 'string' || !secretFromHash('#invite=' + value.secret)) {
    throw new Error('Invalid invitation')
  }
  return { secret: value.secret, expiresAt: value.expiresAt }
}

async function remove(path: string, csrfToken: string, request: typeof fetch): Promise<'ok' | Denied | 'conflict'> {
  const outcome = await sendJson(path, 'DELETE', csrfToken, undefined, request)
  if (outcome === 'unauthenticated' || outcome === 'forbidden') return 'denied'
  if (outcome === 'conflict') return 'conflict'
  if (outcome !== 'ok') throw new Error('Request rejected')
  return 'ok'
}

export const revokeInvitation = (memberId: string, csrfToken: string, request: typeof fetch = fetch) =>
  remove(invitationPath(memberId), csrfToken, request)

export const unlinkAccount = (memberId: string, csrfToken: string, request: typeof fetch = fetch) =>
  remove(accountLinkPath(memberId), csrfToken, request)

export type AcceptOutcome = 'ok' | 'invalid' | 'rate-limited' | 'unavailable'

/** Accepts an invitation for the signed-in account. Every refusal is one generic 'invalid'. */
export async function acceptInvitation(secret: string, csrfToken: string,
  request: typeof fetch = fetch): Promise<AcceptOutcome> {
  const response = await request('/api/v1/invitations/accept', {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
    body: JSON.stringify({ secret }),
  })
  if (response.ok) return 'ok'
  if (response.status === 429) return 'rate-limited'
  if (response.status === 400 || response.status === 401 || response.status === 403) return 'invalid'
  return 'unavailable'
}
