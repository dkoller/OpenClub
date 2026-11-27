import { useState } from 'react'
import type { Language } from './language'
import { AnswerButtons, DateBlock, NoticeLine, eventWords, fill } from './EventBits'
import { formatWhen } from './events'
import { byDay, forPerson, people } from './schedule'
import { routeHash } from './routes'
import { useMyEvents } from './useMyEvents'

const words = {
  de: { venues: 'Belegung der Plätze und Hallen', called: 'Aufgeboten', title: 'Termine', who: 'Für wen', everyone: 'Alle', empty: 'Keine Termine in den nächsten acht Wochen.', emptyFor: 'Für {name} keine Termine in den nächsten acht Wochen.', more: 'Weitere Termine folgen später.', details: 'Fahrten, Helfer und Details' },
  en: { venues: 'When fields and halls are booked', called: 'Called up', title: 'Schedule', who: 'For whom', everyone: 'Everyone', empty: 'No events in the next eight weeks.', emptyFor: 'No events for {name} in the next eight weeks.', more: 'More events follow later.', details: 'Rides, helpers and details' },
  fr: { venues: 'Occupation des terrains et salles', called: 'Convoqué', title: 'Agenda', who: 'Pour qui', everyone: 'Tous', empty: 'Aucun événement dans les huit prochaines semaines.', emptyFor: 'Aucun événement pour {name} dans les huit prochaines semaines.', more: 'D’autres événements suivent plus tard.', details: 'Covoiturage, bénévoles et détails' },
  it: { venues: 'Occupazione di campi e palestre', called: 'Convocato', title: 'Calendario', who: 'Per chi', everyone: 'Tutti', empty: 'Nessun evento nelle prossime otto settimane.', emptyFor: 'Nessun evento per {name} nelle prossime otto settimane.', more: 'Altri eventi seguiranno.', details: 'Passaggi, aiutanti e dettagli' },
} satisfies Record<Language, Record<string, string>>

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
    {/* One list: each card's date block already names the day, so there are no day headings. */}
    {shown.length > 0 && <div className="stack">
      {byDay(shown).flatMap(group => group.events).map(event => {
        return <article key={event.id} className="card event-card" aria-label={event.title}>
          <a className="event-link" href={routeHash({ page: 'event', eventId: event.id })}>
            <DateBlock instant={event.startsAt} language={language} />
            <span className="event-text">
              <span className="tags"><span className="muted small">{event.title !== t[event.kind] ? t[event.kind] + ' · ' : ''}{event.teamName}</span>
                {event.cancelled && <span className="open">{t.cancelled}</span>}
                {!event.cancelled && event.for.some(person => person.squad?.called) && <span className="chip-tag">{t.called}</span>}</span>
              <b>{event.title}</b>
              <span className="muted">{formatWhen(event, language, false)}{event.location ? ' · ' + event.location : ''}</span>
            </span>
          </a>
          {!event.cancelled && event.for.map(person => <AnswerButtons key={person.id} event={event} person={person} self={self}
            language={language} busy={busy} compact onAnswer={(e, id, current, next) => { void respond(e, id, current, next) }} />)}
        </article>
      })}
    </div>}
    {truncated && <p className="muted">{t.more}</p>}
    <p><a href={routeHash({ page: 'venues' })}>{t.venues}</a></p>
    <NoticeLine notice={notice} language={language} />
  </>
}
