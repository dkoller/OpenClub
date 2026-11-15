import { describe, expect, it, vi } from 'vitest'
import { loadClubAccess, signOut } from './auth'

describe('single-club session', () => {
  it('shows signed-out without contacting a club', async () => {
    const request = vi.fn().mockResolvedValue({ status: 401 })
    expect(await loadClubAccess(request)).toEqual({ state: 'signed-out' })
    expect(request).toHaveBeenCalledTimes(1)
  })

  it('does not authorize an unlinked account', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ accountId: 'account', csrfToken: 'secret' }) })
      .mockResolvedValueOnce({ status: 403 })
    expect(await loadClubAccess(request)).toEqual({ state: 'not-member' })
    expect(request.mock.calls[1][0]).toBe('/api/v1/club')
  })

  it('accepts a server-authorized local club', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ accountId: 'account', csrfToken: 'secret' }) })
      .mockResolvedValueOnce({ ok: true, status: 200 })
    expect(await loadClubAccess(request)).toEqual({ state: 'ready', csrfToken: 'secret' })
  })

  it('fails closed for invalid session data or lookup outages', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ personId: 'old' }) })
    expect(await loadClubAccess(request)).toEqual({ state: 'unavailable' })
  })

  it('sends CSRF token and an empty JSON body for logout', async () => {
    const request = vi.fn().mockResolvedValue({ status: 204 })
    expect(await signOut('secret', request)).toBe(true)
    expect(request).toHaveBeenCalledWith('/api/v1/auth/logout', {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': 'secret' },
      body: '{}',
    })
  })
})
