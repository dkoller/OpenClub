import { useEffect, useState, type FormEvent } from 'react'
import { formatDate } from './dates'
import type { Language } from './language'
import { createAbsence, deleteAbsence, readAbsences, type AbsenceList } from './absences'

const words = {
  de: { title: 'Abwesenheiten', intro: 'Für Termine an diesen Tagen ohne Antwort gilt «Nein».', note: 'Hinweis fürs Training (freiwillig)', noteHint: 'Sehen nur die Trainer. Bitte keine Gesundheitsangaben.', none: 'Keine Abwesenheiten.', who: 'Für', me: 'Mich', from: 'Von', to: 'Bis', add: 'Abwesenheit eintragen', remove: 'Löschen', invalid: 'Bitte ab heute, höchstens ein Jahr lang und höchstens zwei Jahre im Voraus.', error: 'Nicht verfügbar.' },
  en: { title: 'Absences', intro: 'Events on these days without an answer count as “No”.', note: 'Note for the coach (optional)', noteHint: 'Only the coaches see it. Please no health details.', none: 'No absences.', who: 'For', me: 'Me', from: 'From', to: 'To', add: 'Add absence', remove: 'Delete', invalid: 'Please start today or later, for at most a year and at most two years ahead.', error: 'Unavailable.' },
  fr: { title: 'Absences', intro: 'Les événements de ces jours sans réponse comptent comme « Non ».', note: 'Remarque pour l’entraîneur (facultatif)', noteHint: 'Seuls les entraîneurs la voient. Pas d’informations de santé, s’il vous plaît.', none: 'Aucune absence.', who: 'Pour', me: 'Moi', from: 'Du', to: 'Au', add: 'Ajouter une absence', remove: 'Supprimer', invalid: 'À partir d’aujourd’hui, pour un an au plus et au plus deux ans à l’avance.', error: 'Indisponible.' },
  it: { title: 'Assenze', intro: 'Gli eventi in questi giorni senza risposta contano come «No».', note: 'Nota per l’allenatore (facoltativa)', noteHint: 'La vedono solo gli allenatori. Niente dati sulla salute, per favore.', none: 'Nessuna assenza.', who: 'Per', me: 'Me', from: 'Dal', to: 'Al', add: 'Aggiungi assenza', remove: 'Elimina', invalid: 'A partire da oggi, al massimo un anno e al massimo due anni in anticipo.', error: 'Non disponibile.' },
} satisfies Record<Language, Record<string, string>>

/** The member's and their children's absences (OpenAPI 1.20.0), on its own page #/family/absences. */
export function Absences({ language, csrfToken, self, onChange }: {
  language: Language; csrfToken: string; self?: string | null; onChange: () => void
}) {
  const t = words[language]
  const [list, setList] = useState<AbsenceList | 'denied' | 'error' | null>(null)
  const [revision, setRevision] = useState(0)
  const [who, setWho] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [note, setNote] = useState('')
  const [notice, setNotice] = useState<'invalid' | 'error' | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let active = true
    readAbsences().then(result => { if (active) setList(result) }, () => { if (active) setList('error') })
    return () => { active = false }
  }, [revision])

  async function run(action: () => Promise<string>) {
    setBusy(true); setNotice(null)
    try {
      const outcome = await action()
      if (outcome === 'invalid') setNotice('invalid')
      else if (outcome === 'denied') setList('denied')
      else { setFrom(''); setTo(''); setNote(''); onChange() }
    } catch { setNotice('error') } finally { setBusy(false); setRevision(value => value + 1) }
  }

  if (list === null || list === 'denied') return null
  if (list === 'error') return <section aria-label={t.title}><h1>{t.title}</h1><p role="alert">{t.error}</p></section>
  const person = who || list.people[0]?.id || ''
  const name = (id: string, firstName: string) => id === self ? t.me : firstName
  function add(event: FormEvent) {
    event.preventDefault()
    void run(() => createAbsence(person, from, to || from, csrfToken, fetch, note))
  }
  return (
    <section aria-label={t.title}>
      <h1>{t.title}</h1>
      <p>{t.intro}</p>
      {list.items.length === 0 ? <p>{t.none}</p> : <ul>{list.items.map(item => <li key={item.id}>
        {name(item.memberId, item.firstName)}: {formatDate(item.from)}{item.to !== item.from ? ` – ${formatDate(item.to)}` : ''}
        {item.note && <span className="muted"> · {item.note}</span>}
        {' '}<button type="button" disabled={busy} onClick={() => { void run(() => deleteAbsence(item.id, csrfToken)) }}>{t.remove}</button>
      </li>)}</ul>}
      <form className="form-grid" onSubmit={add}>
        {list.people.length > 1 && <label>{t.who}<select value={person} onChange={event => setWho(event.target.value)}>
          {list.people.map(p => <option key={p.id} value={p.id}>{name(p.id, p.firstName)}</option>)}
        </select></label>}
        <label>{t.from}<input type="date" value={from} required onChange={event => setFrom(event.target.value)} /></label>
        <label>{t.to}<input type="date" value={to} min={from || undefined} onChange={event => setTo(event.target.value)} /></label>
        <label>{t.note}<input value={note} maxLength={100} aria-describedby="absence-note-hint" onChange={event => setNote(event.target.value)} /></label>
        <p id="absence-note-hint" className="muted small">{t.noteHint}</p>
        <button type="submit" disabled={busy}>{t.add}</button>
      </form>
      {notice && <p role="alert">{t[notice]}</p>}
    </section>
  )
}
