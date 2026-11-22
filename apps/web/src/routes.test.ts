import { describe, expect, it } from 'vitest'
import { parseRoute, routeHash, tabOf } from './routes'

const event = '0b6c3c1e-2f7a-4d55-9a51-1c2e3f405060'

describe('routes', () => {
  it('reads every page and writes it back', () => {
    for (const hash of ['#/', '#/schedule', '#/schedule/' + event, '#/family', '#/team', `#/team/${event}/event/${event}`, '#/admin', '#/admin/members',
      '#/admin/join-requests', '#/admin/fees', '#/admin/permissions', '#/admin/settings']) {
      expect(routeHash(parseRoute(hash))).toBe(hash)
    }
  })

  it('opens Start for empty, unknown, invitation and malformed fragments', () => {
    for (const hash of ['', '#', '#invite=abc', '#/nowhere', '#/admin/secret', '#/schedule/not-an-id', '#/family/x', '#/admin/fees/x', '#/team/x/event/' + event, `#/team/${event}/event/x`, `#/team/${event}/other/${event}`, `#/team/${event}/event/${event}/x`, `#/team/${event}`]) {
      expect(parseRoute(hash)).toEqual({ page: 'start' })
    }
  })

  it('keeps a coach event under the team tab', () => {
    expect(tabOf(parseRoute(`#/team/${event}/event/${event}`))).toBe('team')
  })

  it('keeps an event under the schedule tab', () => {
    expect(tabOf(parseRoute('#/schedule/' + event.toUpperCase()))).toBe('schedule')
    expect(parseRoute('#/schedule/' + event.toUpperCase())).toEqual({ page: 'event', eventId: event })
  })
})
