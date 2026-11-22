/**
 * The app's pages, addressed by the URL fragment (#/schedule, #/admin/fees …) so the static web server needs no
 * rewrites and a reload keeps the page. Unknown fragments, and invitation fragments (#invite=…), open Start.
 */
export type AdminSection = 'overview' | 'members' | 'join-requests' | 'fees' | 'permissions' | 'settings'
export type Route =
  | { page: 'start' }
  | { page: 'schedule' }
  | { page: 'event'; eventId: string }
  | { page: 'family' }
  | { page: 'team' }
  | { page: 'team-event'; teamId: string; eventId: string }
  | { page: 'admin'; section: AdminSection }

const sections: AdminSection[] = ['overview', 'members', 'join-requests', 'fees', 'permissions', 'settings']
const id = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function parseRoute(hash: string): Route {
  const parts = hash.replace(/^#\/?/, '').split('/').filter(Boolean)
  const [first, second, third, fourth, ...more] = parts
  if (first === 'team' && third !== undefined) {
    return id.test(second ?? '') && third === 'event' && fourth !== undefined && id.test(fourth) && more.length === 0
      ? { page: 'team-event', teamId: second!.toLowerCase(), eventId: fourth.toLowerCase() } : { page: 'start' }
  }
  if (third !== undefined) return { page: 'start' }
  if (first === 'schedule' && second === undefined) return { page: 'schedule' }
  if (first === 'schedule' && second !== undefined && id.test(second)) return { page: 'event', eventId: second.toLowerCase() }
  if ((first === 'family' || first === 'team') && second === undefined) return { page: first }
  if (first === 'admin') {
    const section = (second ?? 'overview') as AdminSection
    if (sections.includes(section)) return { page: 'admin', section }
  }
  return { page: 'start' }
}

export function routeHash(route: Route): string {
  switch (route.page) {
    case 'start': return '#/'
    case 'schedule': return '#/schedule'
    case 'event': return '#/schedule/' + route.eventId
    case 'family': return '#/family'
    case 'team': return '#/team'
    case 'team-event': return `#/team/${route.teamId}/event/${route.eventId}`
    case 'admin': return route.section === 'overview' ? '#/admin' : '#/admin/' + route.section
  }
}

/** The tab a page belongs to, for aria-current in the navigation. */
export function tabOf(route: Route): 'start' | 'schedule' | 'family' | 'team' | 'admin' {
  return route.page === 'event' ? 'schedule' : route.page === 'team-event' ? 'team' : route.page
}
