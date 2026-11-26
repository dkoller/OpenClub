import type { Language } from './language'
import { BackLink } from './BackLink'
import { AnswerButtons, NoticeLine, eventWords, fill } from './EventBits'
import { formatWhen } from './events'
import { routeHash } from './routes'
import { ShiftsPanel } from './ShiftsPanel'
import { RidesPanel } from './RidesPanel'
import { useMyEvents } from './useMyEvents'
import { OwnSquadStatus } from './SquadPanel'

const words = {
  de: { back: 'Termine', gone: 'Diesen Termin gibt es nicht mehr, oder er betrifft dich nicht.', notes: 'Hinweise', team: 'Team bisher' },
  en: { back: 'Schedule', gone: 'This event no longer exists, or it does not concern you.', notes: 'Notes', team: 'Team so far' },
  fr: { back: 'Agenda', gone: 'Cet événement n’existe plus ou ne vous concerne pas.', notes: 'Remarques', team: 'Équipe jusqu’ici' },
  it: { back: 'Calendario', gone: 'Questo evento non esiste più o non ti riguarda.', notes: 'Note', team: 'Squadra finora' },
} satisfies Record<Language, Record<string, string>>

/** One event: when and where, the answers of the member and their children, then helper shifts and rides. */
export function EventPage({ eventId, language, csrfToken, self }: { eventId: string; language: Language; csrfToken: string; self: string | null }) {
  const t = { ...eventWords[language], ...words[language] }
  const { events, state, busy, notice, respond } = useMyEvents(csrfToken)
  const event = events?.find(item => item.id === eventId)
  return <>
    <BackLink href={routeHash({ page: 'schedule' })} label={t.back} />
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
      <OwnSquadStatus event={event} self={self} language={language} />
      <ShiftsPanel event={event} language={language} csrfToken={csrfToken} self={self} />
      <RidesPanel event={event} language={language} csrfToken={csrfToken} self={self} />
    </>}
  </>
}
