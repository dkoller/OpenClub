import { describe, expect, it } from 'vitest'
import type { MyEvent } from './events'
import type { Ride, RideList } from './rides'
import { byDay, dayKey, forPerson, missingAnswers, nextSevenDays, people, rideSuggestion } from './schedule'

const now = Date.parse('2026-11-23T08:00:00Z')
const event = (id: string, startsAt: string, forList: MyEvent['for'], cancelled = false): MyEvent => ({
  id, teamId: 't', teamName: 'U12', kind: 'TRAINING', title: id, startsAt,
  endsAt: new Date(Date.parse(startsAt) + 3600000).toISOString(), location: null, notes: null, cancelled,
  counts: { yes: 0, no: 0, maybe: 0 }, for: forList,
})
const me = { id: 'm', firstName: 'Andrea', response: null }
const lea = { id: 'l', firstName: 'Lea', response: null }
const noah = { id: 'n', firstName: 'Noah', response: 'YES' as const }

describe('schedule helpers', () => {
  const events = [
    event('past', '2026-11-22T10:00:00Z', [lea]),
    event('cancelled', '2026-11-24T10:00:00Z', [lea], true),
    event('match', '2026-11-28T09:00:00Z', [lea, noah]),
    event('later', '2026-12-10T09:00:00Z', [me]),
  ]

  it('lists only answers that are missing and still possible', () => {
    expect(missingAnswers(events, now).map(item => `${item.event.id}:${item.person.firstName}`)).toEqual(['match:Lea', 'later:Andrea'])
  })

  it('puts the member first, then children by name', () => {
    expect(people(events, 'm').map(person => person.firstName)).toEqual(['Andrea', 'Lea', 'Noah'])
    expect(people(events, null).map(person => person.firstName)).toEqual(['Andrea', 'Lea', 'Noah'])
  })

  it('filters events and answer rows to one person', () => {
    expect(forPerson(events, null)).toBe(events)
    const noahs = forPerson(events, 'n')
    expect(noahs.map(item => item.id)).toEqual(['match'])
    expect(noahs[0]!.for).toEqual([noah])
  })

  it('groups by local day and keeps the next week', () => {
    expect(byDay(events).map(group => group.events.length)).toEqual([1, 1, 1, 1])
    expect(byDay([events[2]!, events[2]!])).toHaveLength(1)
    expect(dayKey('2026-11-28T09:00:00Z')).toMatch(/^2026-11-28$/)
    expect(nextSevenDays(events, now).map(item => item.id)).toEqual(['cancelled', 'match'])
  })
})

describe('ride suggestion', () => {
  const match = event('match', '2026-11-28T09:00:00Z', [{ id: 'l', firstName: 'Lea', response: 'YES' }, { id: 'n', firstName: 'Noah', response: 'NO' }])
  const ride = (id: string, change: Partial<Ride> = {}): Ride => ({ id, driver: 'Sara K.', mine: false, direction: 'THERE', seats: 3,
    meetingPoint: 'Bahnhof', departsAt: '2026-11-28T08:20:00Z', driverPhone: null, passengers: [], booked: [], ...change })
  const list = (items: Ride[], people = [{ id: 'l', firstName: 'Lea' }, { id: 'n', firstName: 'Noah' }]): RideList => ({ items, people, canOffer: true })
  const taken = { displayName: 'Tim A.', phone: null }

  it('offers the earliest ride there with a free seat to the child who is coming', () => {
    const result = rideSuggestion(match, list([ride('late', { departsAt: '2026-11-28T08:40:00Z' }), ride('early', { passengers: [taken, taken] })]), now)
    expect(result?.ride.id).toBe('early')
    expect(result?.person.firstName).toBe('Lea')
    expect(result?.free).toBe(1)
  })

  it('stays quiet when nobody is coming, the seat is booked, the family drives, or no ride fits', () => {
    expect(rideSuggestion(event('m', '2026-11-28T09:00:00Z', [{ id: 'l', firstName: 'Lea', response: null }]), list([ride('a')]), now)).toBeNull()
    expect(rideSuggestion(match, list([ride('a', { booked: ['l'] }), ride('b')]), now)).toBeNull()
    expect(rideSuggestion(match, list([ride('a', { mine: true }), ride('b')]), now)).toBeNull()
    expect(rideSuggestion(match, list([ride('full', { seats: 1, passengers: [taken] }), ride('back', { direction: 'BACK' })]), now)).toBeNull()
    expect(rideSuggestion(match, list([ride('gone', { departsAt: '2026-11-20T08:00:00Z' })]), now)).toBeNull()
    expect(rideSuggestion(match, list([ride('a')], []), now)).toBeNull()
    expect(rideSuggestion({ ...match, cancelled: true }, list([ride('a')]), now)).toBeNull()
  })
})
