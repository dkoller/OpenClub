import type { Language } from './language'
import { Announcements } from './Announcements'
import { AnswerButtons, DateBlock, NoticeLine, eventWords, shortWhen } from './EventBits'
import { missingAnswers, nextSevenDays } from './schedule'
import { routeHash } from './routes'
import { useMyEvents } from './useMyEvents'

const words = {
  de: { title: 'Start', todo: 'Für dich zu tun', none: 'Alles beantwortet. Neue Termine erscheinen hier, sobald eine Antwort fehlt.', week: 'Sonst in den nächsten 7 Tagen', weekEmpty: 'In den nächsten 7 Tagen steht nichts an.', all: 'Alle Termine' },
  en: { title: 'Start', todo: 'Things for you to do', none: 'Everything is answered. New events show up here as soon as an answer is missing.', week: 'Also in the next 7 days', weekEmpty: 'Nothing in the next 7 days.', all: 'All events' },
  fr: { title: 'Accueil', todo: 'À faire', none: 'Tout est répondu. Les nouveaux événements apparaissent ici dès qu’une réponse manque.', week: 'Aussi dans les 7 prochains jours', weekEmpty: 'Rien dans les 7 prochains jours.', all: 'Tous les événements' },
  it: { title: 'Inizio', todo: 'Da fare', none: 'Hai risposto a tutto. I nuovi eventi compaiono qui appena manca una risposta.', week: 'Anche nei prossimi 7 giorni', weekEmpty: 'Niente nei prossimi 7 giorni.', all: 'Tutti gli eventi' },
} satisfies Record<Language, Record<string, string>>

/** Start: missing answers first (one tap each), the rest of the week, then announcements. */
export function StartPage({ language, csrfToken, self }: { language: Language; csrfToken: string; self: string | null }) {
  const t = { ...eventWords[language], ...words[language] }
  const { events, state, busy, notice, respond } = useMyEvents(csrfToken)
  const todo = events ? missingAnswers(events) : []
  const todoIds = new Set(todo.map(item => item.event.id))
  const week = events ? nextSevenDays(events).filter(event => !todoIds.has(event.id)) : []
  return <>
    <h1>{t.title}</h1>
    {state !== 'denied' && <section aria-labelledby="todo" className="stack">
      <h2 id="todo">{t.todo}{todo.length > 0 && <> <span className="open">{todo.length}</span></>}</h2>
      {state === 'loading' && <p role="status">{t.loading}</p>}
      {state === 'error' && <p role="alert">{t.error}</p>}
      {state === 'ready' && todo.length === 0 && <p className="muted">{t.none}</p>}
      {todo.map(({ event, person }) => <article key={event.id + person.id} className="card event-card" aria-label={`${person.firstName}: ${event.title}`}>
        <a className="event-link" href={routeHash({ page: 'event', eventId: event.id })}>
          <DateBlock instant={event.startsAt} language={language} />
          <span className="event-text"><b>{event.title}</b>
            <span className="muted">{shortWhen(event.startsAt, language)}{event.location ? ' · ' + event.location : ''} · {event.teamName}</span></span>
        </a>
        <AnswerButtons event={event} person={event.for.find(item => item.id === person.id)!} self={self} language={language}
          busy={busy} onAnswer={(e, id, current, next) => { void respond(e, id, current, next) }} />
      </article>)}
      <NoticeLine notice={notice} language={language} />
    </section>}
    {state === 'ready' && <section aria-labelledby="week" className="card list-card">
      <h2 id="week">{t.week}</h2>
      {week.length === 0 ? <p className="muted pad">{t.weekEmpty}</p> : <ul className="rows">{week.map(event => <li key={event.id}>
        <a href={routeHash({ page: 'event', eventId: event.id })} className="row">
          <DateBlock instant={event.startsAt} language={language} />
          <span className="event-text"><b>{event.title}</b>
            <span className="muted">{shortWhen(event.startsAt, language)} · {event.for.map(person => person.id === self ? t.you : person.firstName).join(', ')}</span></span>
          {event.cancelled ? <span className="open">{t.cancelled}</span>
            : <span className="muted small">{event.for.map(person => person.response ? `${person.id === self ? t.you : person.firstName}: ${t[person.response]}` : '').filter(Boolean).join(' · ')}</span>}
        </a></li>)}</ul>}
      <p className="pad"><a href={routeHash({ page: 'schedule' })}>{t.all}</a></p>
    </section>}
    <Announcements language={language} csrfToken={csrfToken} />
  </>
}
