/** Helper duty (OpenAPI 1.32.0): shifts done this season against the club's quota. */
export type MyHelperDuty = { quota: number; done: number; seasonFrom: string; seasonTo: string }
export type HelperDutyRow = { memberId: string; displayName: string; done: number; owed: number }
export type HelperDutyOverview = { quota: number; seasonStartMonth: number; seasonFrom: string; seasonTo: string; items: HelperDutyRow[] }

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
const count = (value: unknown) => typeof value === 'number' && Number.isInteger(value) && value >= 0

async function read(path: string, request: typeof fetch): Promise<Record<string, unknown> | 'denied'> {
  const response = await request(path, { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Helper duty unavailable')
  const body: unknown = await response.json()
  if (!record(body)) throw new Error('Invalid helper duty')
  return body
}

export async function readMyHelperDuty(request: typeof fetch = fetch): Promise<MyHelperDuty | 'denied'> {
  const body = await read('/api/v1/me/helper-duty', request)
  if (body === 'denied') return body
  if (Object.keys(body).length !== 4 || !count(body.quota) || !count(body.done) || typeof body.seasonFrom !== 'string' ||
    typeof body.seasonTo !== 'string') throw new Error('Invalid helper duty')
  return body as MyHelperDuty
}

export async function readHelperDuty(request: typeof fetch = fetch): Promise<HelperDutyOverview | 'denied'> {
  const body = await read('/api/v1/helper-duty', request)
  if (body === 'denied') return body
  if (Object.keys(body).length !== 5 || !count(body.quota) || !count(body.seasonStartMonth) || !Array.isArray(body.items) ||
    !body.items.every(item => record(item) && Object.keys(item).length === 4 && typeof item.memberId === 'string' &&
      typeof item.displayName === 'string' && count(item.done) && count(item.owed))) throw new Error('Invalid helper duty')
  return body as HelperDutyOverview
}

export async function saveHelperDuty(quota: number, seasonStartMonth: number, csrfToken: string,
  request: typeof fetch = fetch): Promise<'ok' | 'invalid' | 'denied'> {
  const response = await request('/api/v1/club/helper-duty', {
    method: 'PUT', credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken, 'Content-Type': 'application/json' }, body: JSON.stringify({ quota, seasonStartMonth }),
  })
  if (response.ok) return 'ok'
  if (response.status === 400) return 'invalid'
  if (response.status === 401 || response.status === 403) return 'denied'
  throw new Error('Saving failed')
}
