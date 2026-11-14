export async function fetchHealth(
  request: typeof fetch = fetch,
): Promise<boolean> {
  const response = await request('/api/health')
  if (!response.ok) return false
  const value: unknown = await response.json()
  return typeof value === 'object' && value !== null &&
    'status' in value && value.status === 'ok'
}
