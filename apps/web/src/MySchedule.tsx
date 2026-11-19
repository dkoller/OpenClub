import { useEffect, useState } from 'react'
import type { Language } from './language'
import { formatWhen, readMyEvents, type MyEvent } from './events'
import { fetchMyProfile } from './myProfile'

const words = {
  de: { title: 'Mein Programm', loading: 'Wird geladen…', empty: 'Keine Termine in den nächsten acht Wochen.', more: 'Weitere Termine folgen später.', error: 'Termine nicht verfügbar.', cancelled: 'Abgesagt', for: 'Für', TRAINING: 'Training', MATCH: 'Spiel', OTHER: 'Termin' },
  en: { title: 'My schedule', loading: 'Loading…', empty: 'No events in the next eight weeks.', more: 'More events follow later.', error: 'Events unavailable.', cancelled: 'Cancelled', for: 'For', TRAINING: 'Training', MATCH: 'Match', OTHER: 'Event' },
  fr: { title: 'Mon programme', loading: 'Chargement…', empty: 'Aucun événement dans les huit prochaines semaines.', more: 'D’autres événements suivent plus tard.', error: 'Événements indisponibles.', cancelled: 'Annulé', for: 'Pour', TRAINING: 'Entraînement', MATCH: 'Match', OTHER: 'Événement' },
  it: { title: 'Il mio programma', loading: 'Caricamento…', empty: 'Nessun evento nelle prossime otto settimane.', more: 'Altri eventi seguiranno.', error: 'Eventi non disponibili.', cancelled: 'Annullato', for: 'Per', TRAINING: 'Allenamento', MATCH: 'Partita', OTHER: 'Evento' },
} satisfies Record<Language, Record<string, string>>

/**
 * The signed-in member's next eight weeks (OpenAPI 1.10.0): their own teams' events and their children's. For a
 * child's event it says whom it is for.
 */
export function MySchedule({ language }: { language: Language }) {
  const t = words[language]
  const [events, setEvents] = useState<MyEvent[] | null>(null)
  const [truncated, setTruncated] = useState(false)
  const [self, setSelf] = useState<string | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'denied' | 'error'>('loading')

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
  }, [])

  if (state === 'denied') return null
  return (
    <section aria-label={t.title}>
      <h2>{t.title}</h2>
      {state === 'loading' && <p role="status">{t.loading}</p>}
      {state === 'error' && <p role="alert">{t.error}</p>}
      {state === 'ready' && events?.length === 0 && <p>{t.empty}</p>}
      {state === 'ready' && events && events.length > 0 && <ul>{events.map(event => {
        const others = event.for.filter(person => person.id !== self)
        return <li key={event.id}>
          <strong>{formatWhen(event, language)}</strong> · {t[event.kind]}: {event.title} · {event.teamName}
          {event.location && <> · {event.location}</>}
          {event.cancelled && <> · <em>{t.cancelled}</em></>}
          {others.length > 0 && <> · {t.for} {others.map(person => person.firstName).join(', ')}</>}
          {event.notes && <p>{event.notes}</p>}
        </li>
      })}</ul>}
      {truncated && <p>{t.more}</p>}
    </section>
  )
}
