import type { Language } from './language'
import { AnswerButtons, NoticeLine, eventWords, fill } from './EventBits'
import { formatWhen } from './events'
import { routeHash } from './routes'
import { ShiftsPanel } from './ShiftsPanel'
import { RidesPanel } from './RidesPanel'
import { useMyEvents } from './useMyEvents'

const words = {
  de: { back: 'Zurück zu den Terminen', gone: 'Diesen Termin gibt es nicht mehr, oder er betrifft dich nicht.', notes: 'Hinweise', team: 'Team bisher' },
  en: { back: 'Back to the schedule', gone: 'This event no longer exists, or it does not concern you.', notes: 'Notes', team: 'Team so far' },
  fr: { back: 'Retour à l’agenda', gone: 'Cet événement n’existe plus ou ne te concerne pas.', notes: 'Remarques', team: 'Équipe jusqu’ici' },
  it: { back: 'Torna al calendario', gone: 'Questo evento non esiste più o non ti riguarda.', notes: 'Note', team: 'Squadra finora' },
} satisfies Record<Language, Record<string, string>>

/** One event: when and where, the answers of the member and their children, then helper shifts and rides. */
export function EventPage({ eventId, language, csrfToken, self }: { eventId: string; language: Language; csrfToken: string; self: string | null }) {
  const t = { ...eventWords[language], ...words[language] }
  const { events, state, busy, notice, respond } = useMyEvents(csrfToken)
  const event = events?.find(item => item.id === eventId)
  return <>
    <p><a href={routeHash({ page: 'schedule' })}>← {t.back}</a></p>
    {state === 'loading' && <p role="status">{t.loading}</p>}
    {state === 'error' && <p role="alert">{t.error}</p>}
    {(state === 'denied' || (state === 'ready' && !event)) && <p>{t.gone}</p>}
    {event && <>
      <h1>{event.title}</h1>
      <section aria-label={formatWhen(event, language)} className="stack">
        <p><b>{formatWhen(event, language)}</b>{event.cancelled && <> <span className="open">{t.cancelled}</span></>}</p>
        <p className="muted">{t[event.kind]} · {event.teamName}{event.location ? ' · ' + event.location : ''}</p>
        {event.notes && <p className="notes">{event.notes}</p>}
      </section>
      {!event.cancelled && <section aria-label={t.comingSelf} className="card pad stack">
        {event.for.map(person => <AnswerButtons key={person.id} event={event} person={person} self={self} language={language}
          busy={busy} onAnswer={(e, id, current, next) => { void respond(e, id, current, next) }} />)}
        <p className="muted small">{t.team}: {fill(t.answers, event.counts)}</p>
        <NoticeLine notice={notice} language={language} />
      </section>}
      <ShiftsPanel event={event} language={language} csrfToken={csrfToken} self={self} />
      <RidesPanel event={event} language={language} csrfToken={csrfToken} self={self} />
    </>}
  </>
}
