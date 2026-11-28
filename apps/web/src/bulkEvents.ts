/** Changing several events at once (OpenAPI 1.29.0), for the teams' managers. */
import type { EventKind } from './events'

export type BulkAction = 'CANCEL' | 'REINSTATE' | 'SET_LOCATION' | 'SET_VENUE' | 'SET_TIMES'
/** `startTime`/`endTime` ("18:30"): SET_TIMES only (OpenAPI 1.40.0). */
export type BulkInput = {
  teamIds: string[]; from: string; to: string; kinds: EventKind[]; action: BulkAction; location: string | null; venueId?: string | null
  startTime?: string; endTime?: string
}
export type BulkOutcome = { matched: number; changed: number } | 'too_many' | 'venue_taken' | 'invalid' | 'denied'

export async function bulkEvents(input: BulkInput, preview: boolean, csrfToken: string,
  request: typeof fetch = fetch): Promise<BulkOutcome> {
  const response = await request('/api/v1/events/bulk', {
    method: 'POST', credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken, 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...input, location: input.action === 'SET_LOCATION' ? input.location : null,
      venueId: input.action === 'SET_VENUE' ? input.venueId ?? null : undefined,
      startTime: input.action === 'SET_TIMES' ? input.startTime : undefined, endTime: input.action === 'SET_TIMES' ? input.endTime : undefined, preview }),
  })
  if (response.status === 409) {
    const code = await response.json().then((value: { code?: string }) => value?.code, () => null)
    return code === 'venue_taken' ? 'venue_taken' : 'too_many'
  }
  if (response.status === 400) return 'invalid'
  if (response.status === 401 || response.status === 403 || response.status === 404) return 'denied'
  if (!response.ok) throw new Error('Bulk change failed')
  const body: unknown = await response.json()
  if (typeof body !== 'object' || body === null || Array.isArray(body) || Object.keys(body).length !== 2 ||
    typeof (body as Record<string, unknown>).matched !== 'number' || typeof (body as Record<string, unknown>).changed !== 'number') {
    throw new Error('Invalid answer')
  }
  return body as { matched: number; changed: number }
}
