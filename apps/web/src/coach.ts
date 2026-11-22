import type { AttendanceList, Counts, TeamEvent } from './events'
import type { Team } from './teams'

/** Members who have not answered: the roster size minus the counted answers (absences already count as No). */
export const notAnswered = (counts: Counts, members: number) => Math.max(0, members - counts.yes - counts.no - counts.maybe)

/** Display names by answer, in roster order; "open" first because that is whom a coach follows up. */
export function byAnswer(list: AttendanceList) {
  const groups = { open: [] as string[], NO: [] as string[], MAYBE: [] as string[], YES: [] as string[] }
  for (const item of list.items) groups[item.response ?? 'open'].push(item.displayName)
  return groups
}

export type UpcomingEvent = { team: Team; event: TeamEvent }

/** The teams' events that have not ended and start within [days], soonest first, at most [max]. */
export function upcoming(teams: { team: Team; events: TeamEvent[] }[], now = Date.now(), days = 14, max = 8): UpcomingEvent[] {
  const end = now + days * 24 * 3600 * 1000
  return teams.flatMap(({ team, events }) => events
    .filter(event => Date.parse(event.endsAt) > now && Date.parse(event.startsAt) < end)
    .map(event => ({ team, event })))
    .sort((a, b) => Date.parse(a.event.startsAt) - Date.parse(b.event.startsAt) || a.team.name.localeCompare(b.team.name))
    .slice(0, max)
}
