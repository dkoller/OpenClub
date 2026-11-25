import { describe, expect, it, vi } from 'vitest'
import { fetchMyProfile, parseMyProfile, replaceMyContacts, setMyPublicListing } from './myProfile'

const profile = {
  id: 'm1', firstName: 'Ada', lastName: 'Example', dateOfBirth: '1985-05-06', email: 'ada@example.invalid',
  phone: '+41000000010', gender: null, isPlayer: true, role: 'MEMBER', teams: [{ id: 't1', name: 'U18' }],
  listedPublicly: true,
  jsPersonNumber: null,
}
const res = (status: number, body?: unknown, etag?: string) => ({ ok: status >= 200 && status < 300, status,
  json: async () => body, headers: new Headers(etag ? { ETag: etag } : {}) })

describe('my profile', () => {
  it('accepts exactly the documented record', () => {
    expect(parseMyProfile(profile)).toEqual(profile)
    for (const broken of [{ ...profile, extra: 1 }, { ...profile, role: 'OWNER' }, { ...profile, teams: [{ id: 't1' }] },
      { ...profile, email: 1 }, { ...profile, isPlayer: 'yes' }, { ...profile, listedPublicly: 'no' }, { ...profile, jsPersonNumber: 12 },
      (({ listedPublicly: _, ...rest }) => rest)(profile), [], null]) {
      expect(() => parseMyProfile(broken)).toThrow()
    }
  })

  it('reads the profile with its version', async () => {
    const request = vi.fn().mockResolvedValue(res(200, profile, '"4"'))
    expect(await fetchMyProfile(request)).toEqual({ profile, version: '"4"' })
    expect(request.mock.calls[0]).toEqual(['/api/v1/me/profile', { credentials: 'same-origin', cache: 'no-store' }])
    expect(await fetchMyProfile(vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    expect(await fetchMyProfile(vi.fn().mockResolvedValue(res(401)))).toBe('denied')
    await expect(fetchMyProfile(vi.fn().mockResolvedValue(res(200, profile)))).rejects.toThrow()
    await expect(fetchMyProfile(vi.fn().mockResolvedValue(res(503)))).rejects.toThrow()
  })

  it('sends only email and phone with the version and CSRF token', async () => {
    const request = vi.fn().mockResolvedValue(res(204))
    expect(await replaceMyContacts(' a@example.invalid ', '', '"4"', 'c'.repeat(43), request)).toBe('ok')
    expect(request.mock.calls[0][0]).toBe('/api/v1/me/profile/contacts')
    expect(request.mock.calls[0][1]).toMatchObject({ method: 'PUT', body: '{"email":"a@example.invalid"}',
      headers: { 'X-CSRF-Token': 'c'.repeat(43), 'Content-Type': 'application/json', 'If-Match': '"4"' } })
    for (const [status, outcome] of [[400, 'invalid'], [412, 'changed'], [401, 'denied'], [403, 'denied']] as const) {
      expect(await replaceMyContacts('', '', '"1"', 'c', vi.fn().mockResolvedValue(res(status)))).toBe(outcome)
    }
    await expect(replaceMyContacts('', '', '"1"', 'c', vi.fn().mockResolvedValue(res(428)))).rejects.toThrow()
  })

  it('switches the public listing with the version and returns the new one', async () => {
    const request = vi.fn().mockResolvedValue(res(204, undefined, '"5"'))
    expect(await setMyPublicListing(false, '"4"', 'c'.repeat(43), request)).toEqual({ outcome: 'ok', version: '"5"' })
    expect(request.mock.calls[0][0]).toBe('/api/v1/me/profile/public-listing')
    expect(request.mock.calls[0][1]).toMatchObject({ method: 'PUT', body: '{"listedPublicly":false}',
      headers: { 'X-CSRF-Token': 'c'.repeat(43), 'Content-Type': 'application/json', 'If-Match': '"4"' } })
    for (const [status, outcome] of [[400, 'invalid'], [412, 'changed'], [401, 'denied'], [403, 'denied']] as const) {
      expect(await setMyPublicListing(true, '"1"', 'c', vi.fn().mockResolvedValue(res(status)))).toEqual({ outcome })
    }
    await expect(setMyPublicListing(true, '"1"', 'c', vi.fn().mockResolvedValue(res(204)))).rejects.toThrow()
    await expect(setMyPublicListing(true, '"1"', 'c', vi.fn().mockResolvedValue(res(428)))).rejects.toThrow()
  })
})
