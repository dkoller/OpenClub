import { describe, expect, it, vi } from 'vitest'
import { archivedPagePath, fetchArchivedPage, fetchMemberPage, memberCreateBody, memberPagePath, type MemberDraft } from './members'

const draft: MemberDraft = {
  firstName: 'Ada', lastName: 'Example', dateOfBirth: '2012-05-01',
  email: '', phone: '', isPlayer: false, gender: '',
}

describe('member directory requests', () => {
  it('builds a bounded, encoded search page with an optional cursor', () => {
    expect(memberPagePath('Müller & Co')).toBe('/api/v1/members?q=M%C3%BCller+%26+Co&limit=20')
    expect(memberPagePath('', 'abc')).toBe('/api/v1/members?q=&limit=20&cursor=abc')
  })

  it('returns the page items and next cursor', async () => {
    const page = { items: [{ id: '1', firstName: 'Ada', lastName: 'Example' }], nextCursor: 'n' }
    const request = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => page })
    expect(await fetchMemberPage('Ada', undefined, undefined, request)).toEqual(page)
  })

  it('reports denial so the admin panel can hide itself', async () => {
    const request = vi.fn().mockResolvedValue({ ok: false, status: 403 })
    expect(await fetchMemberPage('', undefined, undefined, request)).toBe('denied')
  })
})

describe('member create body', () => {
  it('omits empty optional contacts so the server applies the adult/minor rule', () => {
    expect(memberCreateBody(draft)).toEqual({
      firstName: 'Ada', lastName: 'Example', dateOfBirth: '2012-05-01', isPlayer: false,
    })
    expect(memberCreateBody({ ...draft, email: 'ada@example.invalid', phone: '+41000000000' }))
      .toMatchObject({ email: 'ada@example.invalid', phone: '+41000000000' })
  })

  it('never sends gender for non-players', () => {
    expect(memberCreateBody({ ...draft, gender: 'stale value' })).not.toHaveProperty('gender')
  })

  it('sends trimmed gender for players and omits a blank value', () => {
    expect(memberCreateBody({ ...draft, isPlayer: true, gender: '  free text  ' }))
      .toMatchObject({ isPlayer: true, gender: 'free text' })
    expect(memberCreateBody({ ...draft, isPlayer: true, gender: '   ' })).not.toHaveProperty('gender')
  })
})

describe('archived member requests', () => {
  it('uses the archived path with the same query and cursor rules', () => {
    expect(archivedPagePath('Müller', 'c')).toBe('/api/v1/members/archived?q=M%C3%BCller&limit=20&cursor=c')
  })

  it('returns names with archive time, maps denial, and rejects malformed items', async () => {
    const page = { items: [{ id: '1', firstName: 'Ada', lastName: 'Example', archivedAt: '2026-11-19T06:00:00Z' }] }
    const ok = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => page })
    expect(await fetchArchivedPage('', undefined, undefined, ok)).toEqual(page)
    expect(ok.mock.calls[0][0]).toBe('/api/v1/members/archived?q=&limit=20')
    expect(await fetchArchivedPage('', undefined, undefined, vi.fn().mockResolvedValue({ ok: false, status: 403 }))).toBe('denied')
    const bad = { items: [{ id: '1', firstName: 'Ada', lastName: 'Example' }] }
    await expect(fetchArchivedPage('', undefined, undefined,
      vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => bad }))).rejects.toThrow()
  })
})
