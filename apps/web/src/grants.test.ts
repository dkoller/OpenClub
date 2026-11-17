import { describe, expect, it, vi } from 'vitest'
import { capabilities, capabilityChoices, capabilityForScope, fetchGrants, grantChange } from './grants'

describe('grant scope rules', () => {
  it('offers only team capabilities at team scope', () => {
    expect(capabilityChoices('CLUB')).toEqual(capabilities)
    expect(capabilityChoices('TEAM')).toEqual(['VIEW_TEAMS', 'MANAGE_TEAMS'])
  })

  it('resets a club-only capability when switching to team scope', () => {
    expect(capabilityForScope('TEAM', 'MANAGE_PERMISSIONS')).toBe('VIEW_TEAMS')
    expect(capabilityForScope('TEAM', 'EDIT_MEMBERS')).toBe('VIEW_TEAMS')
    expect(capabilityForScope('TEAM', 'MANAGE_TEAMS')).toBe('MANAGE_TEAMS')
    expect(capabilityForScope('CLUB', 'MANAGE_TEAMS')).toBe('MANAGE_TEAMS')
  })

  it('attaches a team only to team-scoped changes', () => {
    expect(grantChange('EDIT_MEMBERS', 'CLUB', 'stale-team')).toEqual({ capability: 'EDIT_MEMBERS', scope: 'CLUB' })
    expect(grantChange('VIEW_TEAMS', 'TEAM', 't1')).toEqual({ capability: 'VIEW_TEAMS', scope: 'TEAM', teamId: 't1' })
  })
})

describe('grant reads', () => {
  it('encodes the member id and fails closed on denial', async () => {
    const request = vi.fn().mockResolvedValue({ ok: false, status: 401 })
    expect(await fetchGrants('a&b', undefined, request)).toBe('denied')
    expect(request.mock.calls[0][0]).toBe('/api/v1/grants?memberId=a%26b')
  })

  it('returns current grants', async () => {
    const items = [{ capability: 'VIEW_TEAMS', scope: 'TEAM', teamId: 't1' }]
    const request = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ items }) })
    expect(await fetchGrants('m1', undefined, request)).toEqual({ items })
  })
})
