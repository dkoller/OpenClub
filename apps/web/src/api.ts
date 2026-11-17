/** Server said the session or permission is missing (401/403); callers must fail closed. */
export type Denied = 'denied'

const read = { credentials: 'same-origin', cache: 'no-store' } as const

function deniedStatus(status: number): boolean {
  return status === 401 || status === 403
}

/**
 * Same-origin, uncached JSON read. Returns 'denied' for 401/403 and throws on any
 * other failure or when the body is not an object with an `items` array.
 */
export async function readItems<T>(path: string, signal?: AbortSignal,
  request: typeof fetch = fetch): Promise<{ items: T[]; nextCursor?: string } | Denied> {
  const response = await request(path, { ...read, ...(signal ? { signal } : {}) })
  if (deniedStatus(response.status)) return 'denied'
  if (!response.ok) throw new Error('Request failed')
  const body: unknown = await response.json()
  if (body === null || typeof body !== 'object' || !('items' in body) || !Array.isArray(body.items)) {
    throw new Error('Invalid response')
  }
  return body as { items: T[]; nextCursor?: string }
}

/** Outcome of a CSRF-protected write, keeping 401 and 403 apart for callers that treat them differently. */
export type WriteOutcome = 'ok' | 'unauthenticated' | 'forbidden' | 'conflict' | 'failed'

/** CSRF-protected same-origin JSON write. Network errors reject; HTTP outcomes resolve. */
export async function sendJson(path: string, method: 'POST' | 'PATCH' | 'DELETE', csrfToken: string,
  body?: object, request: typeof fetch = fetch): Promise<WriteOutcome> {
  const response = await request(path, {
    method,
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  if (response.status === 401) return 'unauthenticated'
  if (response.status === 403) return 'forbidden'
  if (response.status === 409) return 'conflict'
  return response.ok ? 'ok' : 'failed'
}

/** CSRF-protected same-origin write. Returns 'denied' for 401/403 and throws on other failures. */
export async function writeJson(path: string, method: 'POST' | 'PATCH' | 'DELETE', csrfToken: string,
  body?: object, request: typeof fetch = fetch): Promise<'ok' | Denied> {
  const outcome = await sendJson(path, method, csrfToken, body, request)
  if (outcome === 'unauthenticated' || outcome === 'forbidden') return 'denied'
  if (outcome !== 'ok') throw new Error('Request rejected')
  return 'ok'
}
