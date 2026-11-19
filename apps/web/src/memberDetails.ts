import type { Denied } from './api'

/**
 * GET /api/v1/members/{id}/details (OpenAPI 1.3.0). Only fields the caller may read are present:
 * an absent key means "not permitted", null means "readable and not set". Team managers receive only
 * id, names, email, phone and isPlayer; editors receive everything below.
 */
export type MemberDetails = {
  id: string
  firstName: string
  lastName: string
  dateOfBirth?: string | null
  email?: string | null
  phone?: string | null
  gender?: string | null
  isPlayer?: boolean
  archivedAt?: string | null
  hasLinkedLogin?: boolean
  invitation?: { status: 'NONE' | 'OPEN'; expiresAt?: string }
}

export function memberDetailsPath(memberId: string): string {
  return '/api/v1/members/' + encodeURIComponent(memberId) + '/details'
}

const optionalText = ['dateOfBirth', 'email', 'phone', 'gender', 'archivedAt'] as const
const optionalFlag = ['isPlayer', 'hasLinkedLogin'] as const
const known = new Set<string>(['id', 'firstName', 'lastName', 'invitation', ...optionalText, ...optionalFlag])

/** Validates the response shape strictly, so an unexpected field is never rendered. */
export function parseMemberDetails(value: unknown): MemberDetails {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid details')
  const body = value as Record<string, unknown>
  if (Object.keys(body).some(key => !known.has(key))) throw new Error('Unexpected field')
  if (typeof body.id !== 'string' || typeof body.firstName !== 'string' || typeof body.lastName !== 'string') {
    throw new Error('Invalid details')
  }
  for (const key of optionalText) {
    if (key in body && body[key] !== null && typeof body[key] !== 'string') throw new Error('Invalid ' + key)
  }
  for (const key of optionalFlag) {
    if (key in body && typeof body[key] !== 'boolean') throw new Error('Invalid ' + key)
  }
  if ('invitation' in body) {
    const invitation = body.invitation as Record<string, unknown> | null
    if (!invitation || typeof invitation !== 'object' || (invitation.status !== 'NONE' && invitation.status !== 'OPEN') ||
        ('expiresAt' in invitation && typeof invitation.expiresAt !== 'string')) {
      throw new Error('Invalid invitation')
    }
  }
  return body as MemberDetails
}

/** 401/403 is 'denied' (also for members outside the caller's teams), 404 'not-found'; anything else throws. */
export async function fetchMemberDetails(memberId: string, signal?: AbortSignal,
  request: typeof fetch = fetch): Promise<MemberDetails | Denied | 'not-found'> {
  const response = await request(memberDetailsPath(memberId),
    { credentials: 'same-origin', cache: 'no-store', ...(signal ? { signal } : {}) })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (response.status === 404) return 'not-found'
  if (!response.ok) throw new Error('Details unavailable')
  return parseMemberDetails(await response.json())
}

export type EditOutcome = 'ok' | Denied | 'invalid' | 'not-found' | 'conflict'

async function memberWrite(path: string, method: 'PUT' | 'POST', csrfToken: string, body: object | undefined,
  request: typeof fetch): Promise<EditOutcome> {
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

/**
 * PUT /api/v1/members/{id} (OpenAPI 1.4.0) replaces every editable field: an omitted contact or gender is
 * cleared. Build the body only from a form that was filled from [fetchMemberDetails], never from guesses.
 */
export function replaceMember(memberId: string, body: object, csrfToken: string, request: typeof fetch = fetch) {
  return memberWrite('/api/v1/members/' + encodeURIComponent(memberId), 'PUT', csrfToken, body, request)
}

/** POST /api/v1/members/{id}/reactivate: 'conflict' means the record no longer fits the club's current policy. */
export function reactivateMember(memberId: string, csrfToken: string, request: typeof fetch = fetch) {
  return memberWrite('/api/v1/members/' + encodeURIComponent(memberId) + '/reactivate', 'POST', csrfToken, undefined, request)
}

/** The edit form's starting values; only an editor's full read has every field. */
export function draftFromDetails(details: MemberDetails) {
  if (!('dateOfBirth' in details) || typeof details.dateOfBirth !== 'string') throw new Error('Not a full read')
  return {
    firstName: details.firstName, lastName: details.lastName, dateOfBirth: details.dateOfBirth,
    email: details.email ?? '', phone: details.phone ?? '', isPlayer: details.isPlayer ?? false,
    gender: details.gender ?? '',
  }
}
