import { describe, expect, it, vi } from 'vitest'
import { fetchMemberDetails, memberDetailsPath, parseMemberDetails } from './memberDetails'

const res = (status: number, body?: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body })
const manager = { id: 'm1', firstName: 'Ada', lastName: 'Example', email: null, phone: '+41000000000', isPlayer: true }
const editor = { ...manager, dateOfBirth: '1990-01-01', gender: null, archivedAt: null, hasLinkedLogin: false,
  invitation: { status: 'OPEN', expiresAt: '2026-12-01T00:00:00Z' } }

describe('member details', () => {
  it('encodes the member id', () => {
    expect(memberDetailsPath('a/b')).toBe('/api/v1/members/a%2Fb/details')
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
