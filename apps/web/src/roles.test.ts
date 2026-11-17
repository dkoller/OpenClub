import { describe, expect, it, vi } from 'vitest'
import { latestLookup, readRole, rolePath, saveRole } from './roles'

const csrf = 'x'.repeat(43)
const json = (status: number, body?: unknown) =>
  ({ ok: status >= 200 && status < 300, status, json: async () => body })

describe('role requests', () => {
  it('encodes the member id in the role path', () => {
    expect(rolePath('a/b')).toBe('/api/v1/members/a%2Fb/role')
  })

  it('reads a role uncached and forwards the abort signal', async () => {
    const request = vi.fn().mockResolvedValue(json(200, { role: 'ADMIN' }))
    const signal = new AbortController().signal
    expect(await readRole('m1', signal, request)).toBe('ADMIN')
    expect(request).toHaveBeenCalledWith('/api/v1/members/m1/role',
      { credentials: 'same-origin', cache: 'no-store', signal })
  })

  it('fails closed on denial and rejects unknown roles or errors', async () => {
    expect(await readRole('m1', undefined, vi.fn().mockResolvedValue(json(403)))).toBe('denied')
    expect(await readRole('m1', undefined, vi.fn().mockResolvedValue(json(401)))).toBe('denied')
    for (const response of [json(503), json(200, { role: 'OWNER' }), json(200, {}), json(200, null)]) {
      await expect(readRole('m1', undefined, vi.fn().mockResolvedValue(response))).rejects.toThrow()
    }
  })

  it('saves a role with CSRF and maps denial', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, status: 204 })
    expect(await saveRole('m1', 'MEMBER', csrf, request)).toBe('ok')
    expect(request.mock.calls[0][1]).toMatchObject({ method: 'PATCH', body: '{"role":"MEMBER"}' })
    expect(await saveRole('m1', 'MEMBER', csrf, vi.fn().mockResolvedValue({ ok: false, status: 403 }))).toBe('denied')
  })
})

describe('latestLookup', () => {
  it('aborts the previous lookup when a new one starts', () => {
    const lookups = latestLookup()
    const first = lookups.next()
    const second = lookups.next()
    expect(first.aborted).toBe(true)
    expect(second.aborted).toBe(false)
    lookups.cancel()
    expect(second.aborted).toBe(true)
  })

  it('lets only the latest of two overlapping role reads take effect', async () => {
    // Mirrors RoleAdmin: a slow, superseded read must not overwrite the latest member's role.
    const lookups = latestLookup()
    let releaseSlow: (value: unknown) => void = () => {}
    const request = vi.fn()
      .mockImplementationOnce(() => new Promise(resolve => { releaseSlow = resolve }))
      .mockResolvedValueOnce(json(200, { role: 'MEMBER' }))
    const applied: string[] = []
    async function load(id: string) {
      const signal = lookups.next()
      const role = await readRole(id, signal, request)
      if (!signal.aborted) applied.push(`${id}:${role}`)
    }
    const slow = load('slow')
    await load('mara')
    releaseSlow(json(200, { role: 'ADMIN' }))
    await slow
    expect(applied).toEqual(['mara:MEMBER'])
  })
})
