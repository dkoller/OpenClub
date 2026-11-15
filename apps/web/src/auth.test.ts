import { describe, expect, it, vi } from 'vitest'
import { loadClubAccess, signOut } from './auth'

describe('single-club session', () => {
  it('shows signed-out without contacting a club', async () => {
    const request = vi.fn().mockResolvedValue({ status: 401 })
    expect(await loadClubAccess(request)).toEqual({ state: 'signed-out' })
    expect(request).toHaveBeenCalledTimes(1)
    expect(request).toHaveBeenCalledWith('/api/v1/auth/me', { credentials: 'same-origin', cache: 'no-store' })
  })

  it('returns to sign-in if the session expires between the two authorization checks', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ accountId: '7f53bd36-6b66-4ce0-a4ed-3ca1b8411361', csrfToken: 'x'.repeat(43) }) })
      .mockResolvedValueOnce({ status: 401, ok: false })
    expect(await loadClubAccess(request)).toEqual({ state: 'signed-out' })
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/club', { credentials: 'same-origin', cache: 'no-store' })
  })

  it('does not authorize an unlinked account', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ accountId: '7f53bd36-6b66-4ce0-a4ed-3ca1b8411361', csrfToken: 'x'.repeat(43) }) })
      .mockResolvedValueOnce({ status: 403 })
    expect(await loadClubAccess(request)).toEqual({ state: 'not-member', csrfToken: 'x'.repeat(43) })
    expect(request.mock.calls[1][0]).toBe('/api/v1/club')
  })

  it('accepts a server-authorized local club', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ accountId: '7f53bd36-6b66-4ce0-a4ed-3ca1b8411361', csrfToken: 'x'.repeat(43) }) })
      .mockResolvedValueOnce({ ok: true, status: 200,
        json: async () => ({ id: '7f53bd36-6b66-4ce0-a4ed-3ca1b8411360' }) })
    expect(await loadClubAccess(request)).toEqual({ state: 'ready', csrfToken: 'x'.repeat(43) })
  })

  it('does not consider malformed or non-200 club results to be authorized', async () => {
    for (const clubResponse of [
      { ok: true, status: 204 },
      { ok: true, status: 200, json: async () => ({}) },
      { ok: true, status: 200, json: async () => ({ id: 'not-a-uuid' }) },
      { ok: true, status: 200, json: async () => ({ id: '' }) },
    ]) {
      const request = vi.fn()
        .mockResolvedValueOnce({ ok: true, json: async () => ({ accountId: '7f53bd36-6b66-4ce0-a4ed-3ca1b8411361', csrfToken: 'x'.repeat(43) }) })
        .mockResolvedValueOnce(clubResponse)
      expect(await loadClubAccess(request)).toEqual({ state: 'unavailable' })
    }
  })

  it('does not use invalid verified-session identifiers or CSRF values', async () => {
    const id = '7f53bd36-6b66-4ce0-a4ed-3ca1b8411361'
    for (const details of [
      { accountId: '', csrfToken: 'x'.repeat(43) },
      { accountId: 'account', csrfToken: 'x'.repeat(43) },
      { accountId: id, csrfToken: '' },
      { accountId: id, csrfToken: 'x'.repeat(42) },
      { accountId: id, csrfToken: 'x'.repeat(42) + '!' },
    ]) {
      const request = vi.fn().mockResolvedValue({
        status: 200, ok: true, json: async () => details,
      })
      expect(await loadClubAccess(request)).toEqual({ state: 'unavailable' })
      expect(request).toHaveBeenCalledTimes(1)
    }
  })

  it('fails closed for invalid session data or lookup outages', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ personId: 'old' }) })
    expect(await loadClubAccess(request)).toEqual({ state: 'unavailable' })
  })

  it('treats an already expired session as signed out, but not other logout failures', async () => {
    for (const [status, expected] of [[204, true], [401, true], [403, false], [503, false]] as const) {
      const request = vi.fn().mockResolvedValue({ status })
      expect(await signOut('secret', request)).toBe(expected)
    }
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
