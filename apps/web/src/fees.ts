/** Membership fees (OpenAPI 1.12.0). Amounts are whole Swiss cents. */
export type FeePeriod = {
  id: string; name: string; dueDate: string; amountCents: number
  members: number; paid: number; totalCents: number; paidCents: number
}
export type Fee = {
  memberId: string; firstName: string; lastName: string; archived: boolean
  amountCents: number | null; paidOn: string | null
}
export type MyFee = {
  periodId: string; periodName: string; dueDate: string; amountCents: number; paidOn: string | null
  for: { id: string; firstName: string }
}
export type FeeFilter = 'all' | 'open' | 'paid' | 'excluded'

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
const text = (value: unknown) => typeof value === 'string'
const cents = (value: unknown) => typeof value === 'number' && Number.isInteger(value) && value >= 0
const date = (value: unknown) => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)
const exactly = (value: Record<string, unknown>, keys: string[]) =>
  Object.keys(value).length === keys.length && keys.every(key => key in value)

function isPeriod(value: unknown): value is FeePeriod {
  return record(value) && exactly(value, ['id', 'name', 'dueDate', 'amountCents', 'members', 'paid', 'totalCents', 'paidCents']) &&
    text(value.id) && text(value.name) && date(value.dueDate) && cents(value.amountCents) && cents(value.members) &&
    cents(value.paid) && cents(value.totalCents) && cents(value.paidCents)
}

function isFee(value: unknown): value is Fee {
  return record(value) && exactly(value, ['memberId', 'firstName', 'lastName', 'archived', 'amountCents', 'paidOn']) &&
    text(value.memberId) && text(value.firstName) && text(value.lastName) && typeof value.archived === 'boolean' &&
    (value.amountCents === null || cents(value.amountCents)) && (value.paidOn === null || date(value.paidOn))
}

function isMyFee(value: unknown): value is MyFee {
  return record(value) && exactly(value, ['periodId', 'periodName', 'dueDate', 'amountCents', 'paidOn', 'for']) &&
    text(value.periodId) && text(value.periodName) && date(value.dueDate) && cents(value.amountCents) &&
    (value.paidOn === null || date(value.paidOn)) && record(value.for) && exactly(value.for, ['id', 'firstName']) &&
    text(value.for.id) && text(value.for.firstName)
}

async function readList<T>(path: string, valid: (item: unknown) => item is T, request: typeof fetch): Promise<T[] | 'denied'> {
  const response = await request(path, { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Fees unavailable')
  const body: unknown = await response.json()
  if (!record(body) || !exactly(body, ['items']) || !Array.isArray(body.items) || !body.items.every(valid)) {
    throw new Error('Invalid fees')
  }
  return body.items as T[]
}

export const readPeriods = (request: typeof fetch = fetch) => readList('/api/v1/fee-periods', isPeriod, request)

export const readFees = (periodId: string, filter: FeeFilter, request: typeof fetch = fetch) =>
  readList('/api/v1/fee-periods/' + encodeURIComponent(periodId) + '/fees?status=' + filter, isFee, request)

export const readMyFees = (request: typeof fetch = fetch) => readList('/api/v1/me/fees', isMyFee, request)

export type FeeOutcome = 'ok' | 'invalid' | 'denied' | 'not-found' | 'conflict'

async function write(path: string, method: 'POST' | 'PATCH' | 'PUT' | 'DELETE', csrfToken: string,
  body: object | undefined, request: typeof fetch): Promise<FeeOutcome> {
  const response = await request(path, {
    method, credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  if (response.ok) return 'ok'
  if (response.status === 400) return 'invalid'
  if (response.status === 401 || response.status === 403) return 'denied'
  if (response.status === 404) return 'not-found'
  if (response.status === 409) return 'conflict'
  throw new Error('Request failed')
}

const periodPath = (periodId: string) => '/api/v1/fee-periods/' + encodeURIComponent(periodId)

export const createPeriod = (name: string, dueDate: string, amountCents: number, csrfToken: string, request: typeof fetch = fetch) =>
  write('/api/v1/fee-periods', 'POST', csrfToken, { name: name.trim(), dueDate, amountCents }, request)

export const deletePeriod = (periodId: string, confirmation: string, csrfToken: string, request: typeof fetch = fetch) =>
  write(periodPath(periodId), 'DELETE', csrfToken, { name: confirmation }, request)

export const setFee = (periodId: string, memberId: string, amountCents: number, paidOn: string | null, csrfToken: string,
  request: typeof fetch = fetch) =>
  write(periodPath(periodId) + '/fees/' + encodeURIComponent(memberId), 'PUT', csrfToken, { amountCents, paidOn }, request)

export const removeFee = (periodId: string, memberId: string, csrfToken: string, request: typeof fetch = fetch) =>
  write(periodPath(periodId) + '/fees/' + encodeURIComponent(memberId), 'DELETE', csrfToken, undefined, request)

/** "150", "150.5" or "150.50" francs as whole cents; null for anything else or above CHF 100,000. */
export function parseFrancs(value: string): number | null {
  const match = /^(\d{1,6})(?:[.,](\d{1,2}))?$/.exec(value.trim())
  if (!match) return null
  const total = Number(match[1]) * 100 + Number((match[2] ?? '0').padEnd(2, '0'))
  return total <= 10_000_000 ? total : null
}

const locales = { de: 'de-CH', en: 'en-GB', fr: 'fr-CH', it: 'it-CH' } as const

export const formatFrancs = (amountCents: number, language: keyof typeof locales) =>
  new Intl.NumberFormat(locales[language], { style: 'currency', currency: 'CHF' }).format(amountCents / 100)

/** Today's date in the browser's time zone, as YYYY-MM-DD, for "paid today". */
export function today(now = new Date()): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}
