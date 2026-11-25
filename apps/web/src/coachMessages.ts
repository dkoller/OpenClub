/** Coach messages (OpenAPI 1.31.0): an email to part of an event's people, sent by the server. */
export type Audience = 'ALL' | 'YES' | 'NO_ANSWER' | 'SQUAD'
export type MessageOutcome = { members: number } | 'limit' | 'email_off' | 'closed' | 'not_match' | 'invalid' | 'denied'

export async function sendCoachMessage(eventId: string, audience: Audience, subject: string, body: string, csrfToken: string,
  request: typeof fetch = fetch): Promise<MessageOutcome> {
  const response = await request('/api/v1/events/' + encodeURIComponent(eventId) + '/messages', {
    method: 'POST', credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken, 'Content-Type': 'application/json' },
    body: JSON.stringify({ audience, subject, body }),
  })
  if (response.status === 409) {
    const code = await response.json().then((value: { code?: string }) => value?.code, () => null)
    return code === 'limit' || code === 'email_off' || code === 'not_match' ? code : 'closed'
  }
  if (response.status === 400) return 'invalid'
  if (response.status === 401 || response.status === 403 || response.status === 404) return 'denied'
  if (!response.ok) throw new Error('Sending failed')
  const value: unknown = await response.json()
  if (typeof value !== 'object' || value === null || Object.keys(value).length !== 1 ||
    typeof (value as { members?: unknown }).members !== 'number') throw new Error('Invalid answer')
  return value as { members: number }
}
