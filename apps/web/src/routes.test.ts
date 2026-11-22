import { describe, expect, it } from 'vitest'
import { parseRoute, routeHash, tabOf } from './routes'

const event = '0b6c3c1e-2f7a-4d55-9a51-1c2e3f405060'

describe('routes', () => {
  it('reads every page and writes it back', () => {
    for (const hash of ['#/', '#/schedule', '#/schedule/' + event, '#/family', '#/family/profile',
      '#/family/children', '#/family/absences', '#/family/fees', '#/family/calendar', '#/family/notifications', '#/team',
      `#/team/${event}`, `#/team/${event}/schedule`, `#/team/${event}/members`, `#/team/${event}/statistics`,
      `#/team/${event}/event/${event}`, '#/admin', '#/admin/members',
      '#/admin/join-requests', '#/admin/fees', '#/admin/permissions', '#/admin/settings']) {
      expect(routeHash(parseRoute(hash))).toBe(hash)
    }
  })

  it('opens Start for empty, unknown, invitation and malformed fragments', () => {
    for (const hash of ['', '#', '#invite=abc', '#/nowhere', '#/admin/secret', '#/schedule/not-an-id', '#/family/x', '#/family/overview',
      '#/family/fees/x', '#/admin/fees/x', '#/team/x/event/' + event, `#/team/${event}/event/x`, `#/team/${event}/other/${event}`,
      `#/team/${event}/event/${event}/x`, '#/team/x', `#/team/${event}/overview`, `#/team/${event}/schedule/x`,
      `#/team/${event}/members/${event}`]) {
      expect(parseRoute(hash)).toEqual({ page: 'start' })
    }
  })

  it('keeps a coach event under the team tab', () => {
    expect(tabOf(parseRoute(`#/team/${event}/event/${event}`))).toBe('team')
    expect(parseRoute(`#/team/${event}/event/${event}`)).toEqual({ page: 'team-event', teamId: event, eventId: event })
  })

  it('keeps every team page under the team tab', () => {
    expect(parseRoute('#/team')).toEqual({ page: 'team' })
    expect(parseRoute(`#/team/${event.toUpperCase()}`)).toEqual({ page: 'team', teamId: event })
    expect(parseRoute(`#/team/${event}/statistics`)).toEqual({ page: 'team', teamId: event, section: 'statistics' })
    for (const hash of ['#/team', `#/team/${event}`, `#/team/${event}/schedule`, `#/team/${event}/members`, `#/team/${event}/statistics`]) {
      expect(tabOf(parseRoute(hash))).toBe('team')
    }
  })

  it('keeps every part of Family under the family tab', () => {
    expect(parseRoute('#/family')).toEqual({ page: 'family' })
    expect(parseRoute('#/family/fees')).toEqual({ page: 'family', section: 'fees' })
    for (const section of ['profile', 'children', 'absences', 'fees', 'calendar', 'notifications']) {
      expect(tabOf(parseRoute('#/family/' + section))).toBe('family')
    }
  })

  it('keeps an event under the schedule tab', () => {
    expect(tabOf(parseRoute('#/schedule/' + event.toUpperCase()))).toBe('schedule')
    expect(parseRoute('#/schedule/' + event.toUpperCase())).toEqual({ page: 'event', eventId: event })
  })
})
