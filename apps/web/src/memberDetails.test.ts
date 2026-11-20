import { describe, expect, it, vi } from 'vitest'
import { fetchMemberDetails, memberDetailsPath, memberExportPath, parseMemberDetails } from './memberDetails'

const res = (status: number, body?: unknown, etag?: string) => ({ ok: status >= 200 && status < 300, status, json: async () => body,
  headers: new Headers(etag ? { ETag: etag } : {}) })
const manager = { id: 'm1', firstName: 'Ada', lastName: 'Example', email: null, phone: '+41000000000', isPlayer: true }
const editor = { ...manager, dateOfBirth: '1990-01-01', gender: null, archivedAt: null, hasLinkedLogin: false, listedPublicly: true,
  invitation: { status: 'OPEN', expiresAt: '2026-12-01T00:00:00Z' } }

describe('member details', () => {
  it('encodes the member id', () => {
    expect(memberDetailsPath('a/b')).toBe('/api/v1/members/a%2Fb/details')
    expect(memberExportPath('a/b')).toBe('/api/v1/members/a%2Fb/export')
  })

  it('reads uncached and same-origin, and maps outcomes', async () => {
    const ok = vi.fn().mockResolvedValue(res(200, manager))
    expect(await fetchMemberDetails('m1', undefined, ok)).toEqual(manager)
    expect(ok.mock.calls[0]).toEqual(['/api/v1/members/m1/details', { credentials: 'same-origin', cache: 'no-store' }])
    expect(await fetchMemberDetails('m1', undefined, vi.fn().mockResolvedValue(res(200, editor)))).toEqual(editor)
    expect(await fetchMemberDetails('m1', undefined, vi.fn().mockResolvedValue(res(401)))).toBe('denied')
    expect(await fetchMemberDetails('m1', undefined, vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    expect(await fetchMemberDetails('m1', undefined, vi.fn().mockResolvedValue(res(404)))).toBe('not-found')
    await expect(fetchMemberDetails('m1', undefined, vi.fn().mockResolvedValue(res(503)))).rejects.toThrow()
  })

  it('keeps absent fields absent, so the view never invents a value', () => {
    const parsed = parseMemberDetails(manager)
    expect('dateOfBirth' in parsed).toBe(false)
    expect('invitation' in parsed).toBe(false)
  })

  it('rejects malformed or unexpected bodies', () => {
    for (const bad of [null, [], 'x', {}, { ...manager, id: 1 }, { ...manager, accountId: 'a' },
      { ...manager, email: 3 }, { ...manager, isPlayer: 'yes' }, { ...editor, invitation: { status: 'MAYBE' } },
      { ...editor, invitation: null }, { ...editor, invitation: { status: 'OPEN', expiresAt: 1 } }]) {
      expect(() => parseMemberDetails(bad)).toThrow()
    }
  })
})

describe('member edit and reactivation', () => {
  it('replaces with CSRF and JSON, and maps every outcome', async () => {
    const { replaceMember, reactivateMember } = await import('./memberDetails')
    const ok = vi.fn().mockResolvedValue(res(204))
    expect(await replaceMember('a/b', { firstName: 'A' }, '"7"', 'c'.repeat(43), ok)).toBe('ok')
    expect(ok.mock.calls[0][0]).toBe('/api/v1/members/a%2Fb')
    expect(ok.mock.calls[0][1]).toMatchObject({ method: 'PUT', body: '{"firstName":"A"}',
      headers: { 'X-CSRF-Token': 'c'.repeat(43), 'Content-Type': 'application/json', 'If-Match': '"7"' } })
    for (const [status, outcome] of [[401, 'denied'], [403, 'denied'], [400, 'invalid'], [404, 'not-found'], [409, 'conflict']] as const) {
      expect(await replaceMember('m', {}, '"1"', 'c', vi.fn().mockResolvedValue(res(status)))).toBe(outcome)
      expect(await reactivateMember('m', 'c', vi.fn().mockResolvedValue(res(status)))).toBe(outcome)
    }
    expect(await replaceMember('m', {}, '"1"', 'c', vi.fn().mockResolvedValue(res(412)))).toBe('changed')
    await expect(replaceMember('m', {}, '"1"', 'c', vi.fn().mockResolvedValue(res(428)))).rejects.toThrow()
    await expect(replaceMember('m', {}, '"1"', 'c', vi.fn().mockResolvedValue(res(503)))).rejects.toThrow()
    const post = vi.fn().mockResolvedValue(res(204))
    expect(await reactivateMember('m', 'c', post)).toBe('ok')
    expect(post.mock.calls[0]).toEqual(['/api/v1/members/m/reactivate',
      { method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-Token': 'c' } }])
  })

  it('reads the version for the edit form and refuses a read without one', async () => {
    const { fetchMemberForEdit } = await import('./memberDetails')
    expect(await fetchMemberForEdit('m1', vi.fn().mockResolvedValue(res(200, editor, '"3"'))))
      .toEqual({ details: editor, version: '"3"' })
    await expect(fetchMemberForEdit('m1', vi.fn().mockResolvedValue(res(200, editor)))).rejects.toThrow()
    expect(await fetchMemberForEdit('m1', vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    expect(await fetchMemberForEdit('m1', vi.fn().mockResolvedValue(res(404)))).toBe('not-found')
  })

  it('starts the edit form only from a full editor read', async () => {
    const { draftFromDetails } = await import('./memberDetails')
    expect(draftFromDetails(editor as never)).toEqual({ firstName: 'Ada', lastName: 'Example', dateOfBirth: '1990-01-01',
      email: '', phone: '+41000000000', isPlayer: true, gender: '' })
    expect(() => draftFromDetails(manager as never)).toThrow()
  })

  it('switches the public listing at the read version and returns the new one', async () => {
    const { setMemberPublicListing } = await import('./memberDetails')
    const request = vi.fn().mockResolvedValue(res(204, undefined, '"4"'))
    expect(await setMemberPublicListing('a/b', false, '"3"', 'c', request)).toEqual({ outcome: 'ok', version: '"4"' })
    expect(request.mock.calls[0][0]).toBe('/api/v1/members/a%2Fb/public-listing')
    expect(request.mock.calls[0][1]).toMatchObject({ method: 'PUT', body: '{"listedPublicly":false}',
      headers: { 'X-CSRF-Token': 'c', 'Content-Type': 'application/json', 'If-Match': '"3"' } })
    for (const [status, outcome] of [[400, 'invalid'], [404, 'not-found'], [412, 'changed'], [403, 'denied']] as const) {
      expect(await setMemberPublicListing('m1', true, '"1"', 'c', vi.fn().mockResolvedValue(res(status)))).toEqual({ outcome })
    }
    await expect(setMemberPublicListing('m1', true, '"1"', 'c', vi.fn().mockResolvedValue(res(204)))).rejects.toThrow()
    expect(() => parseMemberDetails({ ...editor, listedPublicly: 'yes' })).toThrow()
  })
})
