/** Join requests (OpenAPI 1.22.0): the public form and the editors' list. */
export type JoinForm = { open: boolean; teams: { id: string; name: string }[] }
export type Application = {
  kind: 'SELF' | 'CHILD'; firstName: string; lastName: string; dateOfBirth: string; email: string; phone: string
  parentFirstName: string | null; parentLastName: string | null; teamId: string | null; message: string | null
}
export type JoinRequest = {
  id: string; createdAt: string; kind: 'SELF' | 'CHILD'; firstName: string; lastName: string; dateOfBirth: string
  email: string; phone: string; parentFirstName: string | null; parentLastName: string | null; teamName: string | null
  message: string | null
}

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
const keys = (value: Record<string, unknown>, expected: string[]) =>
  Object.keys(value).length === expected.length && expected.every(key => key in value)
const text = (value: unknown): value is string => typeof value === 'string'
const textOrNull = (value: unknown) => value === null || text(value)
const json = { 'Content-Type': 'application/json' }

export async function readJoinForm(request: typeof fetch = fetch): Promise<JoinForm> {
  const response = await request('/api/v1/public/join-requests', { credentials: 'same-origin', cache: 'no-store' })
  if (!response.ok) throw new Error('Form unavailable')
  const body: unknown = await response.json()
  if (!record(body) || !keys(body, ['open', 'teams']) || typeof body.open !== 'boolean' || !Array.isArray(body.teams) ||
    !body.teams.every(team => record(team) && keys(team, ['id', 'name']) && text(team.id) && text(team.name))) throw new Error('Invalid form')
  return body as JoinForm
}

/** 'closed': the form is off; 'full': too many open requests; 'limited': too many from this connection. */
/** [website] is the hidden honeypot field: people leave it empty. */
export async function submitJoinRequest(application: Application, request: typeof fetch = fetch, website = ''):
  Promise<'ok' | 'invalid' | 'closed' | 'full' | 'limited'> {
  const response = await request('/api/v1/public/join-requests', {
    method: 'POST', credentials: 'same-origin', headers: json,
    body: JSON.stringify({ ...application, acceptedPrivacyNotice: true, website }),
  })
  if (response.status === 202) return 'ok'
  if (response.status === 400) return 'invalid'
  if (response.status === 429) return 'limited'
  if (response.status === 409) {
    const code = await response.json().then(value => (record(value) ? value.code : null), () => null)
    return code === 'full' ? 'full' : 'closed'
  }
  throw new Error('Request failed')
}

export async function readJoinSettings(request: typeof fetch = fetch): Promise<boolean | 'denied'> {
  const response = await request('/api/v1/club/join-requests/settings', { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Settings unavailable')
  const body: unknown = await response.json()
  if (!record(body) || !keys(body, ['open']) || typeof body.open !== 'boolean') throw new Error('Invalid settings')
  return body.open
}

export async function setJoinOpen(open: boolean, csrfToken: string, request: typeof fetch = fetch): Promise<'ok' | 'denied'> {
  const response = await request('/api/v1/club/join-requests/settings', {
    method: 'PUT', credentials: 'same-origin', headers: { ...json, 'X-CSRF-Token': csrfToken }, body: JSON.stringify({ open }),
  })
  if (response.ok) return 'ok'
  if (response.status === 401 || response.status === 403) return 'denied'
  throw new Error('Request failed')
}

export async function readJoinRequests(request: typeof fetch = fetch): Promise<JoinRequest[] | 'denied'> {
  const response = await request('/api/v1/join-requests', { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Requests unavailable')
  const body: unknown = await response.json()
  const fields = ['id', 'createdAt', 'kind', 'firstName', 'lastName', 'dateOfBirth', 'email', 'phone', 'parentFirstName',
    'parentLastName', 'teamName', 'message']
  if (!record(body) || !keys(body, ['items']) || !Array.isArray(body.items) || !body.items.every(item => record(item) &&
    keys(item, fields) && ['SELF', 'CHILD'].includes(item.kind as string) &&
    ['id', 'createdAt', 'firstName', 'lastName', 'dateOfBirth', 'email', 'phone'].every(key => text(item[key])) &&
    ['parentFirstName', 'parentLastName', 'teamName', 'message'].every(key => textOrNull(item[key])))) throw new Error('Invalid requests')
  return body.items as JoinRequest[]
}

/** 'rejected': the member cannot be created as requested; 'gone': handled meanwhile. */
export async function acceptJoinRequest(id: string, csrfToken: string, request: typeof fetch = fetch):
  Promise<'ok' | 'rejected' | 'gone' | 'denied'> {
  const response = await request('/api/v1/join-requests/' + encodeURIComponent(id) + '/accept', {
    method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-Token': csrfToken },
  })
  if (response.status === 201) return 'ok'
  if (response.status === 409) return 'rejected'
  if (response.status === 404) return 'gone'
  if (response.status === 401 || response.status === 403) return 'denied'
  throw new Error('Request failed')
}

export async function declineJoinRequest(id: string, csrfToken: string, request: typeof fetch = fetch): Promise<'ok' | 'gone' | 'denied'> {
  const response = await request('/api/v1/join-requests/' + encodeURIComponent(id), {
    method: 'DELETE', credentials: 'same-origin', headers: { 'X-CSRF-Token': csrfToken },
  })
  if (response.ok) return 'ok'
  if (response.status === 404) return 'gone'
  if (response.status === 401 || response.status === 403) return 'denied'
  throw new Error('Request failed')
}
