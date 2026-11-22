import { describe, expect, it } from 'vitest'
import type { TeamEvent } from './events'
import { byAnswer, notAnswered, upcoming } from './coach'

const now = Date.parse('2026-11-23T08:00:00Z')
const event = (id: string, startsAt: string): TeamEvent => ({ id, teamId: 't', teamName: 'U12', kind: 'TRAINING', title: id,
  startsAt, endsAt: new Date(Date.parse(startsAt) + 3600000).toISOString(), location: null, notes: null, cancelled: false,
  counts: { yes: 0, no: 0, maybe: 0 } })

describe('coach helpers', () => {
  it('counts who has not answered, never below zero', () => {
    expect(notAnswered({ yes: 11, no: 2, maybe: 0 }, 16)).toBe(3)
    expect(notAnswered({ yes: 5, no: 0, maybe: 0 }, 3)).toBe(0)
  })

  it('groups names by answer with the open ones first', () => {
    const groups = byAnswer({ counts: { yes: 1, no: 1, maybe: 1 }, items: [
      { displayName: 'Lea M.', response: 'YES' }, { displayName: 'Mia S.', response: null },
      { displayName: 'Luca F.', response: 'NO' }, { displayName: 'Jonas B.', response: null }, { displayName: 'Ava H.', response: 'MAYBE' }] })
    expect(groups).toEqual({ open: ['Mia S.', 'Jonas B.'], NO: ['Luca F.'], MAYBE: ['Ava H.'], YES: ['Lea M.'] })
  })

  it('merges teams into the next two weeks, soonest first and capped', () => {
    const u12 = { id: 'a', name: 'U12', memberCount: 16 }
    const u9 = { id: 'b', name: 'U9', memberCount: 12 }
    const list = upcoming([
      { team: u12, events: [event('past', '2026-11-22T10:00:00Z'), event('sat', '2026-11-28T09:00:00Z'), event('far', '2026-12-20T09:00:00Z')] },
      { team: u9, events: [event('wed', '2026-11-25T16:30:00Z')] },
    ], now)
    expect(list.map(item => item.event.id)).toEqual(['wed', 'sat'])
    expect(list[0]!.team.name).toBe('U9')
    expect(upcoming([{ team: u12, events: [event('a', '2026-11-24T10:00:00Z'), event('b', '2026-11-25T10:00:00Z')] }], now, 14, 1)).toHaveLength(1)
  })
})
