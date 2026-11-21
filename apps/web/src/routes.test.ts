import { describe, expect, it } from 'vitest'
import { parseRoute, routeHash, tabOf } from './routes'

const event = '0b6c3c1e-2f7a-4d55-9a51-1c2e3f405060'

describe('routes', () => {
  it('reads every page and writes it back', () => {
    for (const hash of ['#/', '#/schedule', '#/schedule/' + event, '#/family', '#/team', '#/admin', '#/admin/members',
      '#/admin/join-requests', '#/admin/fees', '#/admin/permissions', '#/admin/settings']) {
      expect(routeHash(parseRoute(hash))).toBe(hash)
    }
  })

  it('opens Start for empty, unknown, invitation and malformed fragments', () => {
    for (const hash of ['', '#', '#invite=abc', '#/nowhere', '#/admin/secret', '#/schedule/not-an-id', '#/family/x', '#/admin/fees/x']) {
      expect(parseRoute(hash)).toEqual({ page: 'start' })
    }
  })

  it('keeps an event under the schedule tab', () => {
    expect(tabOf(parseRoute('#/schedule/' + event.toUpperCase()))).toBe('schedule')
    expect(parseRoute('#/schedule/' + event.toUpperCase())).toEqual({ page: 'event', eventId: event })
  })
})
