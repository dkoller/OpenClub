import { useState } from 'react'
import type { Language } from './language'
import { AnswerButtons, DateBlock, NoticeLine, eventWords, fill } from './EventBits'
import { formatWhen } from './events'
import { byDay, forPerson, people } from './schedule'
import { routeHash } from './routes'
import { useMyEvents } from './useMyEvents'

const words = {
  de: { title: 'Termine', who: 'Für wen', everyone: 'Alle', empty: 'Keine Termine in den nächsten acht Wochen.', emptyFor: 'Für {name} keine Termine in den nächsten acht Wochen.', more: 'Weitere Termine folgen später.', details: 'Fahrten, Helfer und Details' },
  en: { title: 'Schedule', who: 'For whom', everyone: 'Everyone', empty: 'No events in the next eight weeks.', emptyFor: 'No events for {name} in the next eight weeks.', more: 'More events follow later.', details: 'Rides, helpers and details' },
  fr: { title: 'Agenda', who: 'Pour qui', everyone: 'Tous', empty: 'Aucun événement dans les huit prochaines semaines.', emptyFor: 'Aucun événement pour {name} dans les huit prochaines semaines.', more: 'D’autres événements suivent plus tard.', details: 'Covoiturage, bénévoles et détails' },
  it: { title: 'Calendario', who: 'Per chi', everyone: 'Tutti', empty: 'Nessun evento nelle prossime otto settimane.', emptyFor: 'Nessun evento per {name} nelle prossime otto settimane.', more: 'Altri eventi seguiranno.', details: 'Passaggi, aiutanti e dettagli' },
} satisfies Record<Language, Record<string, string>>

const dayTitle = (day: string, language: Language) => new Intl.DateTimeFormat(
  { de: 'de-CH', en: 'en-GB', fr: 'fr-CH', it: 'it-CH' }[language], { weekday: 'long', day: '2-digit', month: '2-digit' },
).format(new Date(day + 'T12:00:00'))

/** The next eight weeks by day, filtered by family member, with answers in place. */
export function SchedulePage({ language, csrfToken, self }: { language: Language; csrfToken: string; self: string | null }) {
  const t = { ...eventWords[language], ...words[language] }
  const { events, truncated, state, busy, notice, respond } = useMyEvents(csrfToken)
  const [personId, setPersonId] = useState<string | null>(null)
  const everyone = events ? people(events, self) : []
  const shown = events ? forPerson(events, personId) : []
  const chosen = everyone.find(person => person.id === personId)
  return <>
    <h1>{t.title}</h1>
    {state === 'loading' && <p role="status">{t.loading}</p>}
    {state === 'error' && <p role="alert">{t.error}</p>}
    {state === 'ready' && everyone.length > 1 && <div role="group" aria-label={t.who} className="chips">
      <button type="button" className={personId === null ? 'btn chip on' : 'btn chip'} aria-pressed={personId === null}
        onClick={() => setPersonId(null)}>{t.everyone}</button>
      {everyone.map(person => <button key={person.id} type="button" className={personId === person.id ? 'btn chip on' : 'btn chip'}
        aria-pressed={personId === person.id} onClick={() => setPersonId(person.id)}>{person.id === self ? t.you : person.firstName}</button>)}
    </div>}
    {state === 'ready' && shown.length === 0 && <p className="muted">{chosen ? fill(t.emptyFor, { name: chosen.firstName }) : t.empty}</p>}
    {byDay(shown).map(group => <section key={group.day} aria-label={dayTitle(group.day, language)} className="stack">
      <h2 className="day">{dayTitle(group.day, language)}</h2>
      {group.events.map(event => {
        const missing = !event.cancelled && event.for.some(person => person.response === null) && Date.parse(event.startsAt) > Date.now()
        return <article key={event.id} className="card event-card" aria-label={event.title}>
          <a className="event-link" href={routeHash({ page: 'event', eventId: event.id })}>
            <DateBlock instant={event.startsAt} language={language} />
            <span className="event-text">
              <span className="tags"><span className="muted small">{t[event.kind]} · {event.teamName}</span>
                {event.cancelled && <span className="open">{t.cancelled}</span>}
                {missing && <span className="open">{t.missing}</span>}</span>
              <b>{event.title}</b>
              <span className="muted">{formatWhen(event, language)}{event.location ? ' · ' + event.location : ''}</span>
            </span>
          </a>
          {!event.cancelled && event.for.map(person => <AnswerButtons key={person.id} event={event} person={person} self={self}
            language={language} busy={busy} onAnswer={(e, id, current, next) => { void respond(e, id, current, next) }} />)}
          <a className="small" href={routeHash({ page: 'event', eventId: event.id })}>{t.details}</a>
        </article>
      })}
    </section>)}
    {truncated && <p className="muted">{t.more}</p>}
    <NoticeLine notice={notice} language={language} />
  </>
}
