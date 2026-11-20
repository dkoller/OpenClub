import { useEffect, useState } from 'react'
import type { Language } from './language'
import { ShiftsPanel } from './ShiftsPanel'
import { CalendarFeed } from './CalendarFeed'
import { answerable, answerAttendance, clearAttendance, formatWhen, readMyEvents, type AttendanceResponse, type MyEvent } from './events'
import { fetchMyProfile } from './myProfile'

const words = {
  de: { showShifts: 'Helfereinsätze zeigen', hideShifts: 'Helfereinsätze ausblenden', title: 'Mein Programm', loading: 'Wird geladen…', empty: 'Keine Termine in den nächsten acht Wochen.', more: 'Weitere Termine folgen später.', error: 'Termine nicht verfügbar.', cancelled: 'Abgesagt', for: 'Für', TRAINING: 'Training', MATCH: 'Spiel', OTHER: 'Termin', you: 'Du', YES: 'Ja', NO: 'Nein', MAYBE: 'Vielleicht', answers: 'Antworten', closed: 'Antworten nicht mehr möglich.', denied: 'Keine Berechtigung.', failed: 'Antwort nicht gespeichert.' },
  en: { showShifts: 'Show volunteer shifts', hideShifts: 'Hide volunteer shifts', title: 'My schedule', loading: 'Loading…', empty: 'No events in the next eight weeks.', more: 'More events follow later.', error: 'Events unavailable.', cancelled: 'Cancelled', for: 'For', TRAINING: 'Training', MATCH: 'Match', OTHER: 'Event', you: 'You', YES: 'Yes', NO: 'No', MAYBE: 'Maybe', answers: 'Answers', closed: 'Answers are closed for this event.', denied: 'No permission.', failed: 'Answer not saved.' },
  fr: { showShifts: 'Afficher le bénévolat', hideShifts: 'Masquer le bénévolat', title: 'Mon programme', loading: 'Chargement…', empty: 'Aucun événement dans les huit prochaines semaines.', more: 'D’autres événements suivent plus tard.', error: 'Événements indisponibles.', cancelled: 'Annulé', for: 'Pour', TRAINING: 'Entraînement', MATCH: 'Match', OTHER: 'Événement', you: 'Vous', YES: 'Oui', NO: 'Non', MAYBE: 'Peut-être', answers: 'Réponses', closed: 'Les réponses sont closes pour cet événement.', denied: 'Pas d’autorisation.', failed: 'Réponse non enregistrée.' },
  it: { showShifts: 'Mostra turni di volontariato', hideShifts: 'Nascondi turni di volontariato', title: 'Il mio programma', loading: 'Caricamento…', empty: 'Nessun evento nelle prossime otto settimane.', more: 'Altri eventi seguiranno.', error: 'Eventi non disponibili.', cancelled: 'Annullato', for: 'Per', TRAINING: 'Allenamento', MATCH: 'Partita', OTHER: 'Evento', you: 'Tu', YES: 'Sì', NO: 'No', MAYBE: 'Forse', answers: 'Risposte', closed: 'Le risposte sono chiuse per questo evento.', denied: 'Nessuna autorizzazione.', failed: 'Risposta non salvata.' },
} satisfies Record<Language, Record<string, string>>

/**
 * The signed-in member's next eight weeks (OpenAPI 1.10.0): their own teams' events and their children's. For a
 * child's event it says whom it is for.
 */
export function MySchedule({ language, csrfToken }: { language: Language; csrfToken: string }) {
  const t = words[language]
  const [events, setEvents] = useState<MyEvent[] | null>(null)
  const [truncated, setTruncated] = useState(false)
  const [self, setSelf] = useState<string | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'denied' | 'error'>('loading')
  const [revision, setRevision] = useState(0)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<'closed' | 'denied' | 'failed' | null>(null)
  const [shiftsFor, setShiftsFor] = useState<string | null>(null)

  async function respond(event: MyEvent, memberId: string, current: AttendanceResponse | null, next: AttendanceResponse) {
    setBusy(true); setNotice(null)
    try {
      // Choosing the current answer again clears it.
      const outcome = current === next ? await clearAttendance(event.id, memberId, csrfToken)
        : await answerAttendance(event.id, memberId, next, csrfToken)
      if (outcome !== 'ok') setNotice(outcome)
    } catch { setNotice('failed') } finally { setBusy(false); setRevision(value => value + 1) }
  }

  useEffect(() => {
    let active = true
    void (async () => {
      try {
        const [result, profile] = await Promise.all([readMyEvents(), fetchMyProfile().catch(() => 'denied' as const)])
        if (!active) return
        if (result === 'denied') { setState('denied'); return }
        setEvents(result.items)
        setTruncated(result.truncated)
        setSelf(profile === 'denied' ? null : profile.profile.id)
        setState('ready')
      } catch { if (active) setState('error') }
    })()
    return () => { active = false }
  }, [revision])

  if (state === 'denied') return null
  return (
    <section aria-label={t.title}>
      <h2>{t.title}</h2>
      {state === 'loading' && <p role="status">{t.loading}</p>}
      {state === 'error' && <p role="alert">{t.error}</p>}
      {state === 'ready' && events?.length === 0 && <p>{t.empty}</p>}
      {state === 'ready' && events && events.length > 0 && <ul>{events.map(event => {
        const others = event.for.filter(person => person.id !== self)
        const open = answerable(event)
        return <li key={event.id}>
          <strong>{formatWhen(event, language)}</strong> · {t[event.kind]}: {event.title} · {event.teamName}
          {event.location && <> · {event.location}</>}
          {event.cancelled && <> · <em>{t.cancelled}</em></>}
          {others.length > 0 && <> · {t.for} {others.map(person => person.firstName).join(', ')}</>}
          {event.notes && <p>{event.notes}</p>}
          <p>{t.answers}: {event.counts.yes} {t.YES}, {event.counts.no} {t.NO}, {event.counts.maybe} {t.MAYBE}</p>
          {event.for.map(person => {
            const who = person.id === self ? t.you : person.firstName
            return <div key={person.id} role="group" aria-label={`${who}: ${event.title}`}>
              {who}:{' '}
              {(['YES', 'NO', 'MAYBE'] as const).map(choice => <button key={choice} type="button"
                aria-pressed={person.response === choice} disabled={busy || !open}
                onClick={() => { void respond(event, person.id, person.response, choice) }}>{t[choice]}</button>)}
            </div>
          })}
          <button type="button" aria-expanded={shiftsFor === event.id}
            onClick={() => setShiftsFor(shiftsFor === event.id ? null : event.id)}>
            {shiftsFor === event.id ? t.hideShifts : t.showShifts}</button>
          {shiftsFor === event.id && <ShiftsPanel event={event} language={language} csrfToken={csrfToken} self={self} />}
        </li>
      })}</ul>}
      {truncated && <p>{t.more}</p>}
      {notice && <p role="alert">{t[notice]}</p>}
      {state === 'ready' && <CalendarFeed language={language} csrfToken={csrfToken} />}
    </section>
  )
}
