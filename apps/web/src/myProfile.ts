/** The signed-in member's own record (OpenAPI 1.9.0): read it, and replace only one's own email and phone. */
export type MyProfile = {
  id: string
  firstName: string
  lastName: string
  dateOfBirth: string
  email: string | null
  phone: string | null
  gender: string | null
  isPlayer: boolean
  role: 'ADMIN' | 'MEMBER'
  teams: { id: string; name: string }[]
}

const keys = ['id', 'firstName', 'lastName', 'dateOfBirth', 'email', 'phone', 'gender', 'isPlayer', 'role', 'teams']
const text = (value: unknown) => typeof value === 'string'
const textOrNull = (value: unknown) => value === null || typeof value === 'string'

/** Accepts exactly the documented keys and types, so an unexpected field is never shown by accident. */
export function parseMyProfile(body: unknown): MyProfile {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) throw new Error('Invalid profile')
  const value = body as Record<string, unknown>
  const valid = Object.keys(value).length === keys.length && keys.every(key => key in value) &&
    text(value.id) && text(value.firstName) && text(value.lastName) && text(value.dateOfBirth) &&
    textOrNull(value.email) && textOrNull(value.phone) && textOrNull(value.gender) &&
    typeof value.isPlayer === 'boolean' && (value.role === 'ADMIN' || value.role === 'MEMBER') &&
    Array.isArray(value.teams) && value.teams.every(team => typeof team === 'object' && team !== null &&
      Object.keys(team).length === 2 && text((team as Record<string, unknown>).id) && text((team as Record<string, unknown>).name))
  if (!valid) throw new Error('Invalid profile')
  return value as MyProfile
}

/** 'denied' when the signed-in account is not an active member (401/403); anything else unexpected throws. */
export async function fetchMyProfile(request: typeof fetch = fetch):
  Promise<{ profile: MyProfile; version: string } | 'denied'> {
  const response = await request('/api/v1/me/profile', { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Profile unavailable')
  const version = response.headers.get('ETag')
  if (!version) throw new Error('No version')
  return { profile: parseMyProfile(await response.json()), version }
}

/** 'changed' (412): someone changed the record after it was read; read it again. An empty field is cleared. */
export type ContactsOutcome = 'ok' | 'invalid' | 'changed' | 'denied'

export async function replaceMyContacts(email: string, phone: string, version: string, csrfToken: string,
  request: typeof fetch = fetch): Promise<ContactsOutcome> {
  const response = await request('/api/v1/me/profile/contacts', {
    method: 'PUT', credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken, 'Content-Type': 'application/json', 'If-Match': version },
    body: JSON.stringify({ ...(email.trim() ? { email: email.trim() } : {}), ...(phone.trim() ? { phone: phone.trim() } : {}) }),
  })
  if (response.ok) return 'ok'
  if (response.status === 400) return 'invalid'
  if (response.status === 412) return 'changed'
  if (response.status === 401 || response.status === 403) return 'denied'
  throw new Error('Request failed')
}
