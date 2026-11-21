import { answerable, type MyEvent } from './events'

export type Person = { id: string; firstName: string }

/** Every answer still missing for the member or their children, soonest first; only events that can still be answered. */
export function missingAnswers(events: MyEvent[], now = Date.now()): { event: MyEvent; person: Person }[] {
  return events.filter(event => answerable(event, now)).flatMap(event => event.for
    .filter(person => person.response === null)
    .map(person => ({ event, person: { id: person.id, firstName: person.firstName } })))
}

/** The people the schedule concerns, the member first, then children by first name. */
export function people(events: MyEvent[], self: string | null): Person[] {
  const seen = new Map<string, Person>()
  for (const event of events) for (const person of event.for) if (!seen.has(person.id)) seen.set(person.id, { id: person.id, firstName: person.firstName })
  return [...seen.values()].sort((a, b) => a.id === self ? -1 : b.id === self ? 1 : a.firstName.localeCompare(b.firstName))
}

/** Events concerning one person (null: everyone), with only that person's answer rows. */
export function forPerson(events: MyEvent[], personId: string | null): MyEvent[] {
  if (personId === null) return events
  return events.filter(event => event.for.some(person => person.id === personId))
    .map(event => ({ ...event, for: event.for.filter(person => person.id === personId) }))
}

/** The local calendar day of an instant as YYYY-MM-DD. */
export function dayKey(instant: string): string {
  const date = new Date(instant)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

/** Events grouped by their local start day, in order. */
export function byDay(events: MyEvent[]): { day: string; events: MyEvent[] }[] {
  const groups: { day: string; events: MyEvent[] }[] = []
  for (const event of [...events].sort((a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt))) {
    const day = dayKey(event.startsAt)
    const last = groups[groups.length - 1]
    if (last?.day === day) last.events.push(event)
    else groups.push({ day, events: [event] })
  }
  return groups
}

/** Events starting within the next seven days. */
export function nextSevenDays(events: MyEvent[], now = Date.now()): MyEvent[] {
  const end = now + 7 * 24 * 3600 * 1000
  return events.filter(event => Date.parse(event.endsAt) > now && Date.parse(event.startsAt) < end)
}
