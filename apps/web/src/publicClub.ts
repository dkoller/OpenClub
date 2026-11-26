/**
 * The club's name for visitors and not-yet-members (OpenAPI 1.35.0), sent without credentials. Null when there is
 * no club yet or the answer is anything but exactly `{ name }`.
 */
export async function readPublicClubName(request: typeof fetch = fetch): Promise<string | null> {
  const response = await request('/api/v1/public/club', { credentials: 'omit', cache: 'no-store' })
  if (!response.ok) return null
  const value: unknown = await response.json()
  if (typeof value !== 'object' || value === null || Object.keys(value).length !== 1) return null
  const name = (value as { name?: unknown }).name
  return typeof name === 'string' && name.trim() !== '' ? name : null
}
