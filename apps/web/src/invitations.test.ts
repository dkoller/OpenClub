import { describe, expect, it, vi } from 'vitest'
import { acceptInvitation, accountLinkPath, createInvitation, invitationLink, invitationPath, revokeInvitation,
  secretFromHash, unlinkAccount } from './invitations'

const csrf = 'x'.repeat(43)
const secret = 'A'.repeat(43)
const res = (status: number, body?: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body })

describe('invitation requests', () => {
  it('encodes member ids and keeps the secret in the fragment', () => {
    expect(invitationPath('a/b')).toBe('/api/v1/members/a%2Fb/invitation')
    expect(accountLinkPath('a/b')).toBe('/api/v1/members/a%2Fb/account-link')
    expect(invitationLink('https://club.example', secret)).toBe('https://club.example/#invite=' + secret)
  })

  it('only reads a well formed secret from the hash', () => {
    expect(secretFromHash('#invite=' + secret)).toBe(secret)
    for (const bad of ['', '#invite=', '#invite=short', '#other=' + secret, '#invite=' + secret + 'x', '#invite=' + '!'.repeat(43)]) {
      expect(secretFromHash(bad)).toBeNull()
    }
  })

  it('creates an invitation with CSRF and maps outcomes', async () => {
    const ok = vi.fn().mockResolvedValue(res(201, { secret, expiresAt: '2026-01-01T00:00:00Z' }))
    expect(await createInvitation('m1', csrf, ok)).toEqual({ secret, expiresAt: '2026-01-01T00:00:00Z' })
    expect(ok.mock.calls[0][1]).toMatchObject({ method: 'POST', headers: { 'X-CSRF-Token': csrf } })
    expect(await createInvitation('m1', csrf, vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    expect(await createInvitation('m1', csrf, vi.fn().mockResolvedValue(res(401)))).toBe('denied')
    expect(await createInvitation('m1', csrf, vi.fn().mockResolvedValue(res(409)))).toBe('conflict')
    for (const bad of [res(503), res(201, {}), res(201, null), res(201, { secret: 'short', expiresAt: 'x' })]) {
      await expect(createInvitation('m1', csrf, vi.fn().mockResolvedValue(bad))).rejects.toThrow()
    }
  })

  it('revokes and unlinks with CSRF and maps outcomes', async () => {
    const ok = vi.fn().mockResolvedValue(res(204))
    expect(await revokeInvitation('m1', csrf, ok)).toBe('ok')
    expect(ok.mock.calls[0]).toEqual(['/api/v1/members/m1/invitation', expect.objectContaining({ method: 'DELETE' })])
    expect(await unlinkAccount('m1', csrf, ok)).toBe('ok')
    expect(ok.mock.calls[1][0]).toBe('/api/v1/members/m1/account-link')
    expect(await unlinkAccount('m1', csrf, vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    expect(await unlinkAccount('m1', csrf, vi.fn().mockResolvedValue(res(409)))).toBe('conflict')
    await expect(unlinkAccount('m1', csrf, vi.fn().mockResolvedValue(res(503)))).rejects.toThrow()
  })

  it('accepts with the secret in the body and maps every failure generically', async () => {
    const ok = vi.fn().mockResolvedValue(res(204))
    expect(await acceptInvitation(secret, csrf, ok)).toBe('ok')
    expect(ok.mock.calls[0][1]).toMatchObject({ method: 'POST', body: JSON.stringify({ secret }) })
    expect(await acceptInvitation(secret, csrf, vi.fn().mockResolvedValue(res(400)))).toBe('invalid')
    expect(await acceptInvitation(secret, csrf, vi.fn().mockResolvedValue(res(429)))).toBe('rate-limited')
    expect(await acceptInvitation(secret, csrf, vi.fn().mockResolvedValue(res(503)))).toBe('unavailable')
  })
})
