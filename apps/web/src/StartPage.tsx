import { useEffect, useState } from 'react'
import type { Language } from './language'
import { Announcements } from './Announcements'
import { formatTime } from './dates'
import { AnswerButtons, DateBlock, NoticeLine, eventWords, fill, shortWhen } from './EventBits'
import { missingAnswers, nextSevenDays, rideSuggestion } from './schedule'
import { readRides, seat, type Ride } from './rides'
import type { MyEvent } from './events'
import type { Person } from './schedule'
import { routeHash } from './routes'
import { useMyEvents } from './useMyEvents'

const words = {
  de: { showAll: 'Alle {n} anzeigen', title: 'Start', todo: 'Für dich zu tun', none: 'Alles beantwortet. Neue Termine erscheinen hier, sobald eine Antwort fehlt.', week: 'Sonst in den nächsten 7 Tagen', weekEmpty: 'In den nächsten 7 Tagen steht nichts an.', all: 'Alle Termine', rideFor: 'Fahrt für {name}: {when}', rideDetail: '{driver} fährt um {time} ab {place}', free: '{n} Platz frei', freeMany: '{n} Plätze frei', book: 'Platz buchen', allRides: 'Alle Fahrten', booked: 'Platz gebucht. Die Telefonnummer der Fahrerin oder des Fahrers steht beim Termin.', rideFailed: 'Platz nicht gebucht. Vielleicht ist er schon weg; bitte beim Termin nachsehen.' },
  en: { showAll: 'Show all {n}', title: 'Start', todo: 'Things for you to do', none: 'Everything is answered. New events show up here as soon as an answer is missing.', week: 'Also in the next 7 days', weekEmpty: 'Nothing in the next 7 days.', all: 'All events', rideFor: 'Ride for {name}: {when}', rideDetail: '{driver} leaves at {time} from {place}', free: '{n} seat free', freeMany: '{n} seats free', book: 'Book seat', allRides: 'All rides', booked: 'Seat booked. The driver’s phone number is on the event.', rideFailed: 'Seat not booked. It may be gone; please check the event.' },
  fr: { showAll: 'Afficher les {n}', title: 'Accueil', todo: 'À faire', none: 'Tout est répondu. Les nouveaux événements apparaissent ici dès qu’une réponse manque.', week: 'Aussi dans les 7 prochains jours', weekEmpty: 'Rien dans les 7 prochains jours.', all: 'Tous les événements', rideFor: 'Trajet pour {name} : {when}', rideDetail: '{driver} part à {time} de {place}', free: '{n} place libre', freeMany: '{n} places libres', book: 'Réserver une place', allRides: 'Tous les trajets', booked: 'Place réservée. Le numéro du conducteur figure sur l’événement.', rideFailed: 'Place non réservée. Elle est peut-être déjà prise ; vérifiez l’événement.' },
  it: { showAll: 'Mostra tutti i {n}', title: 'Inizio', todo: 'Da fare', none: 'Hai risposto a tutto. I nuovi eventi compaiono qui appena manca una risposta.', week: 'Anche nei prossimi 7 giorni', weekEmpty: 'Niente nei prossimi 7 giorni.', all: 'Tutti gli eventi', rideFor: 'Passaggio per {name}: {when}', rideDetail: '{driver} parte alle {time} da {place}', free: '{n} posto libero', freeMany: '{n} posti liberi', book: 'Prenota posto', allRides: 'Tutti i passaggi', booked: 'Posto prenotato. Il telefono dell’autista è nell’evento.', rideFailed: 'Posto non prenotato. Forse è già preso; controlla l’evento.' },
} satisfies Record<Language, Record<string, string>>

type Suggestion = { event: MyEvent; person: Person; ride: Ride; free: number }

const time = (instant: string, language: Language) => new Intl.DateTimeFormat(
  { de: 'de-CH', en: 'en-GB', fr: 'fr-CH', it: 'it-CH' }[language], { hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(instant))

/** Rides worth booking: events of the next 7 days someone of the family said Yes to, at most five lookups. */
function useRideSuggestions(events: MyEvent[] | null) {
  const [suggestions, setSuggestions] = useState<Suggestion[]>([])
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    const candidates = (events ? nextSevenDays(events) : [])
      .filter(event => !event.cancelled && event.for.some(person => person.response === 'YES')).slice(0, 5)
    void Promise.all(candidates.map(event => readRides(event.id).then(rides => {
      if (rides === 'denied') return null
      const found = rideSuggestion(event, rides)
      return found ? { event, ...found } : null
    }).catch(() => null))).then(found => { if (active) setSuggestions(found.filter((item): item is Suggestion => item !== null)) })
    return () => { active = false }
  }, [events, revision])
  return { suggestions, reload: () => setRevision(value => value + 1) }
}

/** Start: missing answers first (one tap each), a ride to book, the rest of the week, then announcements. */
/** Things to do shows the next three answers first, so announcements stay within reach. */
const FIRST = 3

export function StartPage({ language, csrfToken, self }: { language: Language; csrfToken: string; self: string | null }) {
  const t = { ...eventWords[language], ...words[language] }
  const { events, state, busy, notice, respond } = useMyEvents(csrfToken)
  const { suggestions, reload } = useRideSuggestions(events)
  const [booking, setBooking] = useState(false)
  const [rideNotice, setRideNotice] = useState<{ ok: boolean; text: string } | null>(null)
  async function book(item: Suggestion) {
    setBooking(true); setRideNotice(null)
    try {
      const outcome = await seat(item.ride.id, item.person.id, false, csrfToken)
      setRideNotice(outcome === 'ok' ? { ok: true, text: t.booked } : { ok: false, text: t.rideFailed })
    } catch { setRideNotice({ ok: false, text: t.rideFailed }) } finally { setBooking(false); reload() }
  }
  const [showAll, setShowAll] = useState(false)
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
      {(showAll ? todo : todo.slice(0, FIRST)).map(({ event, person }) => <article key={event.id + person.id} className="card event-card" aria-label={`${person.firstName}: ${event.title}`}>
        <a className="event-link" href={routeHash({ page: 'event', eventId: event.id })}>
          <DateBlock instant={event.startsAt} language={language} />
          <span className="event-text"><b>{event.title}</b>
            <span className="muted">{formatTime(event.startsAt)}{event.location ? ' · ' + event.location : ''} · {event.teamName}</span></span>
        </a>
        <AnswerButtons event={event} person={event.for.find(item => item.id === person.id)!} self={self} language={language}
          busy={busy} onAnswer={(e, id, current, next) => { void respond(e, id, current, next) }} />
      </article>)}
      {!showAll && todo.length > FIRST && <p><button type="button" className="btn" onClick={() => setShowAll(true)}>{fill(t.showAll, { n: todo.length })}</button></p>}
      <NoticeLine notice={notice} language={language} />
      {suggestions.map(item => <article key={item.event.id + item.person.id} className="card event-card"
        aria-label={fill(t.rideFor, { name: item.person.firstName, when: shortWhen(item.event.startsAt, language) })}>
        <div className="event-text">
          <b>{fill(t.rideFor, { name: item.person.firstName, when: shortWhen(item.event.startsAt, language) })}</b>
          <span className="muted">{fill(t.rideDetail, { driver: item.ride.driver, time: time(item.ride.departsAt, language), place: item.ride.meetingPoint })}
            {' · '}{fill(item.free === 1 ? t.free : t.freeMany, { n: item.free })}</span>
        </div>
        <div className="actions">
          <button type="button" className="btn primary" disabled={booking} onClick={() => { void book(item) }}>{t.book}</button>
          <a className="btn" href={routeHash({ page: 'event', eventId: item.event.id })}>{t.allRides}</a>
        </div>
      </article>)}
      {rideNotice && <p role={rideNotice.ok ? 'status' : 'alert'} className={rideNotice.ok ? undefined : 'notice'}>{rideNotice.text}</p>}
    </section>}
    {state === 'ready' && <section aria-labelledby="week" className="card list-card">
      <h2 id="week">{t.week}</h2>
      {week.length === 0 ? <p className="muted pad">{t.weekEmpty}</p> : <ul className="rows">{week.map(event => <li key={event.id}>
        <a href={routeHash({ page: 'event', eventId: event.id })} className="row">
          <DateBlock instant={event.startsAt} language={language} />
          <span className="event-text"><b>{event.title}</b>
            <span className="muted">{formatTime(event.startsAt)} · {event.for.map(person => person.id === self ? t.you : person.firstName).join(', ')}</span></span>
          {event.cancelled ? <span className="open">{t.cancelled}</span>
            : <span className="muted small">{event.for.map(person => person.response ? `${person.id === self ? t.you : person.firstName}: ${t[person.response]}` : '').filter(Boolean).join(' · ')}</span>}
        </a></li>)}</ul>}
      <p className="pad"><a href={routeHash({ page: 'schedule' })}>{t.all}</a></p>
    </section>}
    <Announcements language={language} csrfToken={csrfToken} />
  </>
}
