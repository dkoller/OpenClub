/** The member's private calendar feed link (OpenAPI 1.17.0). The link is shown only once, when it is created. */
export type FeedStatus = { active: boolean; createdAt: string | null }

const path = '/api/v1/me/calendar-feed'
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

export async function readFeedStatus(request: typeof fetch = fetch): Promise<FeedStatus | 'denied'> {
  const response = await request(path, { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Feed unavailable')
  const body: unknown = await response.json()
  if (!record(body) || Object.keys(body).length !== 2 || typeof body.active !== 'boolean' ||
    !(body.createdAt === null || typeof body.createdAt === 'string')) throw new Error('Invalid feed status')
  return body as FeedStatus
}

/** Creates a new link (any earlier one stops working) and returns it; it is never shown again. */
export async function createFeed(csrfToken: string, request: typeof fetch = fetch): Promise<string | 'denied'> {
  const response = await request(path, { method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-Token': csrfToken } })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Request failed')
  const body: unknown = await response.json()
  if (!record(body) || Object.keys(body).length !== 1 || typeof body.url !== 'string' ||
    !/^https?:\/\/[^/]+\/api\/v1\/calendar\/[A-Za-z0-9_-]{43}$/.test(body.url)) throw new Error('Invalid feed link')
  return body.url
}

export async function revokeFeed(csrfToken: string, request: typeof fetch = fetch): Promise<'ok' | 'denied'> {
  const response = await request(path, { method: 'DELETE', credentials: 'same-origin', headers: { 'X-CSRF-Token': csrfToken } })
  if (response.ok) return 'ok'
  if (response.status === 401 || response.status === 403) return 'denied'
  throw new Error('Request failed')
}
