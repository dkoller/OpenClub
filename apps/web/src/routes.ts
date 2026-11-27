/**
 * The app's pages, addressed by the URL fragment (#/schedule, #/admin/fees …) so the static web server needs no
 * rewrites and a reload keeps the page. Unknown fragments, and invitation fragments (#invite=…), open Start.
 */
export type AdminSection = 'overview' | 'members' | 'join-requests' | 'fees' | 'venues' | 'permissions' | 'settings'
/** One part of Family, on its own page; without a section, #/family is the overview linking to them. */
export type FamilySection = 'profile' | 'children' | 'absences' | 'fees' | 'calendar' | 'notifications'
/** One part of a team, on its own page; without a section, #/team/<teamId> is the team's page linking to them. */
export type TeamSection = 'schedule' | 'members' | 'statistics'
/** One task of Administration → Members, on its own page; without a part, the member list and form. */
export type MemberPart = 'invitations' | 'guardians' | 'roles' | 'transfer' | 'archived' | 'public'
export type Route =
  | { page: 'start' }
  | { page: 'schedule' }
  /** When each field and hall is booked (OpenAPI 1.30.0), under Schedule. */
  | { page: 'venues' }
  | { page: 'event'; eventId: string }
  | { page: 'family'; section?: FamilySection }
  | { page: 'team'; teamId?: string; section?: TeamSection }
  | { page: 'team-event'; teamId: string; eventId: string }
  | { page: 'admin'; section: AdminSection; part?: MemberPart }

const sections: AdminSection[] = ['overview', 'members', 'join-requests', 'fees', 'venues', 'permissions', 'settings']
const familySections: FamilySection[] = ['profile', 'children', 'absences', 'fees', 'calendar', 'notifications']
export const teamSections: TeamSection[] = ['schedule', 'members', 'statistics']
export const memberParts: MemberPart[] = ['invitations', 'guardians', 'roles', 'transfer', 'archived', 'public']
const id = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function parseRoute(hash: string): Route {
  const parts = hash.replace(/^#\/?/, '').split('/').filter(Boolean)
  const [first, second, third, fourth, ...more] = parts
  if (first === 'team' && second !== undefined) {
    if (!id.test(second)) return { page: 'start' }
    const teamId = second.toLowerCase()
    if (third === undefined) return { page: 'team', teamId }
    if (teamSections.includes(third as TeamSection) && fourth === undefined) return { page: 'team', teamId, section: third as TeamSection }
    return third === 'event' && fourth !== undefined && id.test(fourth) && more.length === 0
      ? { page: 'team-event', teamId, eventId: fourth.toLowerCase() } : { page: 'start' }
  }
  if (first === 'admin' && second === 'members' && third !== undefined) {
    return memberParts.includes(third as MemberPart) && fourth === undefined
      ? { page: 'admin', section: 'members', part: third as MemberPart } : { page: 'start' }
  }
  if (third !== undefined) return { page: 'start' }
  if (first === 'schedule' && second === undefined) return { page: 'schedule' }
  if (first === 'schedule' && second === 'venues') return { page: 'venues' }
  if (first === 'schedule' && second !== undefined && id.test(second)) return { page: 'event', eventId: second.toLowerCase() }
  if ((first === 'family' || first === 'team') && second === undefined) return { page: first }
  if (first === 'family' && familySections.includes(second as FamilySection)) return { page: 'family', section: second as FamilySection }
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
    case 'venues': return '#/schedule/venues'
    case 'event': return '#/schedule/' + route.eventId
    case 'family': return route.section ? '#/family/' + route.section : '#/family'
    case 'team': return route.teamId ? `#/team/${route.teamId}${route.section ? '/' + route.section : ''}` : '#/team'
    case 'team-event': return `#/team/${route.teamId}/event/${route.eventId}`
    case 'admin': return route.section === 'overview' ? '#/admin' : '#/admin/' + route.section + (route.part ? '/' + route.part : '')
  }
}

/** The tab a page belongs to, for aria-current in the navigation. */
export function tabOf(route: Route): 'start' | 'schedule' | 'family' | 'team' | 'admin' {
  return route.page === 'event' || route.page === 'venues' ? 'schedule' : route.page === 'team-event' ? 'team' : route.page
}
