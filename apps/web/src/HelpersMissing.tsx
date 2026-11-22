import { useEffect, useState } from 'react'
import type { Language } from './language'
import { readUnfilled, type UnfilledShift } from './shifts'
import { fill, shortWhen } from './EventBits'

export const helperWords = {
  de: { title: 'Helfer fehlen', window: 'Helfereinsätze der nächsten 14 Tage mit freien Plätzen.', none: 'In den nächsten 14 Tagen sind alle Helfereinsätze besetzt.', places: '{task}: {free} von {needed} Plätzen frei', detail: '{event} · {team} · {when}', more: 'Es gibt noch mehr; die nächsten 100 sind aufgeführt.', error: 'Helfereinsätze sind gerade nicht verfügbar.' },
  en: { title: 'Helpers missing', window: 'Helper shifts of the next 14 days with free places.', none: 'All helper shifts of the next 14 days are filled.', places: '{task}: {free} of {needed} places free', detail: '{event} · {team} · {when}', more: 'There are more; the next 100 are listed.', error: 'Helper shifts are unavailable right now.' },
  fr: { title: 'Bénévoles manquants', window: 'Tâches des 14 prochains jours avec des places libres.', none: 'Toutes les tâches des 14 prochains jours sont pourvues.', places: '{task} : {free} places libres sur {needed}', detail: '{event} · {team} · {when}', more: 'Il y en a d’autres ; les 100 prochaines sont affichées.', error: 'Les tâches sont indisponibles pour le moment.' },
  it: { title: 'Mancano aiutanti', window: 'Turni dei prossimi 14 giorni con posti liberi.', none: 'Tutti i turni dei prossimi 14 giorni sono coperti.', places: '{task}: {free} posti liberi su {needed}', detail: '{event} · {team} · {when}', more: 'Ce ne sono altri; sono elencati i prossimi 100.', error: 'I turni non sono disponibili al momento.' },
} satisfies Record<Language, Record<string, string>>

export const describeShift = (shift: UnfilledShift, language: Language) => ({
  title: fill(helperWords[language].places, { task: shift.title, free: shift.needed - shift.filled, needed: shift.needed }),
  detail: fill(helperWords[language].detail, { event: shift.eventTitle, team: shift.teamName, when: shortWhen(shift.startsAt, language) }),
})

/** On the Team page: shifts of the teams this person manages that still need people. Hidden for anyone else. */
export function HelpersMissing({ language }: { language: Language }) {
  const t = helperWords[language]
  const [list, setList] = useState<{ items: UnfilledShift[]; truncated: boolean } | 'denied' | 'error' | null>(null)
  useEffect(() => {
    let active = true
    readUnfilled().then(result => { if (active) setList(result) }).catch(() => { if (active) setList('error') })
    return () => { active = false }
  }, [])
  if (list === null || list === 'denied') return null
  return <section aria-labelledby="helpers-missing" className="card pad stack">
    <h2 id="helpers-missing">{t.title}</h2>
    {list === 'error' ? <p role="alert">{t.error}</p> : <>
      <p className="muted small">{list.items.length === 0 ? t.none : t.window}</p>
      {list.items.length > 0 && <ul className="rows">{list.items.map(shift => {
        const text = describeShift(shift, language)
        return <li key={shift.id} className="row"><span className="event-text"><b>{text.title}</b><span className="muted">{text.detail}</span></span></li>
      })}</ul>}
      {list.truncated && <p className="muted small">{t.more}</p>}
    </>}
  </section>
}
