export type PublicName = { displayName: string }

/**
 * Anonymous display names, sent without credentials. Rejects any entry that has
 * fields besides displayName, so a server bug cannot leak private data into the page.
 */
export async function readPublicNames(signal?: AbortSignal, request: typeof fetch = fetch): Promise<PublicName[]> {
  const response = await request('/api/v1/public/members',
    { credentials: 'omit', cache: 'no-store', ...(signal ? { signal } : {}) })
  if (!response.ok) throw new Error('Member directory unavailable')
  const value: unknown = await response.json()
  if (!Array.isArray(value) || !value.every(item =>
    typeof item === 'object' && item !== null &&
    Object.keys(item).length === 1 && typeof (item as PublicName).displayName === 'string'
  )) throw new Error('Unexpected directory response')
  return value as PublicName[]
}
