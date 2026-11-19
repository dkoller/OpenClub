import type { Language } from './language'

/** Member list as CSV for editors (OpenAPI 1.13.0). */
export const memberListExportPath = (language: Language) => '/api/v1/members/export.csv?lang=' + language

export type ImportStatus = 'OK' | 'DUPLICATE' | 'INVALID'
export type ImportProblem = 'FIRST_NAME' | 'LAST_NAME' | 'DATE_OF_BIRTH' | 'EMAIL' | 'PHONE' | 'GENDER' | 'PLAYER' |
  'ADULT_CONTACTS' | 'GENDER_REQUIRED' | 'DUPLICATE_IN_FILE'
export type ImportResult = {
  rows: { line: number; firstName: string; lastName: string; status: ImportStatus; problems: ImportProblem[] }[]
  counts: { ok: number; duplicate: number; invalid: number }
  created: number | null
}

const problems = new Set(['FIRST_NAME', 'LAST_NAME', 'DATE_OF_BIRTH', 'EMAIL', 'PHONE', 'GENDER', 'PLAYER',
  'ADULT_CONTACTS', 'GENDER_REQUIRED', 'DUPLICATE_IN_FILE'])
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
const count = (value: unknown) => typeof value === 'number' && Number.isInteger(value) && value >= 0

function isResult(value: unknown): value is ImportResult {
  if (!record(value) || Object.keys(value).length !== 3 || !Array.isArray(value.rows) || !record(value.counts)) return false
  const counts = value.counts
  return Object.keys(counts).length === 3 && count(counts.ok) && count(counts.duplicate) && count(counts.invalid) &&
    (value.created === null || count(value.created)) &&
    value.rows.every(row => record(row) && Object.keys(row).length === 5 && count(row.line) &&
      typeof row.firstName === 'string' && typeof row.lastName === 'string' &&
      (row.status === 'OK' || row.status === 'DUPLICATE' || row.status === 'INVALID') &&
      Array.isArray(row.problems) && row.problems.every(problem => typeof problem === 'string' && problems.has(problem)))
}

/** Sends the file's raw bytes (the server detects UTF-8 or Windows-1252). 'unreadable' is a 400: not a usable file. */
export async function uploadMemberList(file: Blob, mode: 'preview' | 'commit', csrfToken: string,
  request: typeof fetch = fetch): Promise<ImportResult | 'unreadable' | 'denied'> {
  const response = await request('/api/v1/members/import?mode=' + mode, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken, 'Content-Type': 'text/csv' }, body: file,
  })
  if (response.status === 400) return 'unreadable'
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Import failed')
  const body: unknown = await response.json()
  if (!isResult(body)) throw new Error('Invalid import result')
  return body
}
