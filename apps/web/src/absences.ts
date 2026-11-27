/** Absences of the member and their minors (OpenAPI 1.20.0). Dates are YYYY-MM-DD in the club's time zone. */
/** `note`: at most 100 characters, seen by the coaches next to the implied No (OpenAPI 1.39.0). */
export type Absence = { id: string; memberId: string; firstName: string; from: string; to: string; note: string | null }
export type AbsenceList = { items: Absence[]; people: { id: string; firstName: string }[] }

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
const exactly = (value: Record<string, unknown>, keys: string[]) =>
  Object.keys(value).length === keys.length && keys.every(key => typeof value[key] === 'string')
const day = /^\d{4}-\d{2}-\d{2}$/

export function parseAbsences(body: unknown): AbsenceList {
  const valid = record(body) && Object.keys(body).length === 2 &&
    Array.isArray(body.items) && body.items.every(item => record(item) &&
      Object.keys(item).length === 6 && ['id', 'memberId', 'firstName', 'from', 'to'].every(key => typeof item[key] === 'string') &&
      (item.note === null || typeof item.note === 'string') && day.test(item.from as string) && day.test(item.to as string)) &&
    Array.isArray(body.people) && body.people.every(person => record(person) && exactly(person, ['id', 'firstName']))
  if (!valid) throw new Error('Invalid absences')
  return body as AbsenceList
}

export async function readAbsences(request: typeof fetch = fetch): Promise<AbsenceList | 'denied'> {
  const response = await request('/api/v1/me/absences', { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Absences unavailable')
  return parseAbsences(await response.json())
}

export async function createAbsence(memberId: string, from: string, to: string, csrfToken: string,
  request: typeof fetch = fetch, note = ''): Promise<'ok' | 'invalid' | 'denied'> {
  const response = await request('/api/v1/me/absences', {
    method: 'POST', credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken, 'Content-Type': 'application/json' }, body: JSON.stringify({ memberId, from, to, ...(note.trim() ? { note: note.trim() } : {}) }),
  })
  if (response.ok) return 'ok'
  if (response.status === 400) return 'invalid'
  if (response.status === 401 || response.status === 403) return 'denied'
  throw new Error('Request failed')
}

export async function deleteAbsence(id: string, csrfToken: string, request: typeof fetch = fetch): Promise<'ok' | 'denied'> {
  const response = await request('/api/v1/absences/' + encodeURIComponent(id), {
    method: 'DELETE', credentials: 'same-origin', headers: { 'X-CSRF-Token': csrfToken },
  })
  if (response.ok) return 'ok'
  if (response.status === 401 || response.status === 403) return 'denied'
  throw new Error('Request failed')
}
