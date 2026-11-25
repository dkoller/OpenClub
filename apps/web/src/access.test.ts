import { describe, expect, it } from 'vitest'
import { hasAdmin, loadAccess, noAccess } from './access'

const json = (status: number, body: unknown = {}) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

function server(allowed: (path: string) => boolean) {
  const calls: string[] = []
  const request = (async (input: RequestInfo | URL) => {
    const path = String(input)
    calls.push(path)
    if (!allowed(path)) return json(403)
    if (path.startsWith('/api/v1/teams')) return json(200, { items: [{ id: 't', name: 'U12', memberCount: 3 }] })
    if (path.startsWith('/api/v1/grants')) return json(200, { items: [] })
    if (path === '/api/v1/venues') return json(200, { canManage: true, items: [] })
    return json(200, { items: [] })
  }) as typeof fetch
  return { request, calls }
}

describe('loadAccess', () => {
  it('shows nothing extra to a plain member', async () => {
    const { request, calls } = server(() => false)
    const access = await loadAccess(request)
    expect(access).toEqual(noAccess)
    expect(hasAdmin(access)).toBe(false)
    expect(calls).toHaveLength(6)
  })

  it('maps each read-only probe to its entry', async () => {
    const { request } = server(path => path.startsWith('/api/v1/teams') || path.startsWith('/api/v1/fee-periods'))
    expect(await loadAccess(request)).toEqual({ ...noAccess, teams: true, fees: true })
    const all = await loadAccess(server(() => true).request)
    expect(all).toEqual({ teams: true, editor: true, directory: true, fees: true, permissions: true, venues: true })
    expect(hasAdmin(all)).toBe(true)
  })

  it('keeps the team tab for a club-wide holder of a club without teams', async () => {
    const empty = (async (input: RequestInfo | URL) => String(input).startsWith('/api/v1/teams')
      ? json(200, { items: [] }) : json(403)) as typeof fetch
    expect(await loadAccess(empty)).toEqual({ ...noAccess, teams: true })
  })

  it('hides only on denial: other failures keep the entry so its page can offer a retry', async () => {
    const unavailable = (async () => json(503)) as typeof fetch
    expect(await loadAccess(unavailable)).toEqual({ teams: true, editor: true, directory: true, fees: true, permissions: true, venues: true })
    const signedOut = (async () => json(401)) as typeof fetch
    expect(await loadAccess(signedOut)).toEqual(noAccess)
  })
})
