/** Per-club adult age bounds (ADR-0007); the database CHECK and API enforce the same range. */
export const ADULT_AGE_MIN = 13
export const ADULT_AGE_MAX = 25

export function isAdultAge(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value)
    && value >= ADULT_AGE_MIN && value <= ADULT_AGE_MAX
}

/** Parses a number-input value; returns null unless it is a whole number within the bounds. */
export function parseAdultAge(input: string): number | null {
  const trimmed = input.trim()
  if (!/^\d{1,2}$/.test(trimmed)) return null
  const value = Number(trimmed)
  return isAdultAge(value) ? value : null
}

export type AdultAgeResult = 'saved' | 'denied' | 'conflict' | 'error'

/**
 * Saves the club's adult age. 409 means existing members would become adults
 * without the required contacts; the server never says which members.
 */
export async function saveAdultAge(years: number, csrfToken: string,
  request: typeof fetch = fetch): Promise<AdultAgeResult> {
  try {
    const response = await request('/api/v1/club/adult-age-policy', {
      method: 'PATCH',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
      body: JSON.stringify({ adultAgeYears: years }),
    })
    if (response.status === 401 || response.status === 403) return 'denied'
    if (response.status === 409) return 'conflict'
    return response.ok ? 'saved' : 'error'
  } catch {
    return 'error'
  }
}
