import { useState, type FormEvent } from 'react'
import type { Language } from './language'
import type { EventKind } from './events'
import { bulkEvents, type BulkAction, type BulkInput } from './bulkEvents'

const words = {
  de: { title: 'Mehrere Termine ändern', hint: 'Zum Beispiel alle Trainings in den Herbstferien absagen oder in eine andere Halle verlegen. Betrifft nur Termine, die noch nicht begonnen haben. Alle Betroffenen erhalten eine einzige E-Mail.', from: 'Von', to: 'Bis', kinds: 'Welche Termine', TRAINING: 'Trainings', MATCH: 'Spiele', OTHER: 'Andere Termine', action: 'Was tun', CANCEL: 'Absagen', REINSTATE: 'Absage aufheben', SET_LOCATION: 'Neuer Ort', location: 'Ort', check: 'Prüfen', preview: '{n} Termine sind betroffen.', none: 'Keine Termine betroffen.', confirm: '{n} Termine ändern', done: '{n} Termine geändert.', tooMany: 'Zu viele Termine (höchstens 500). Bitte den Zeitraum verkleinern.', invalid: 'Bitte Angaben prüfen (höchstens ein Jahr, mindestens eine Art).', failed: 'Nicht möglich. Bitte später nochmals versuchen.' },
  en: { title: 'Change several events', hint: 'For example, cancel all trainings during the autumn holidays or move them to another hall. Only events that have not started are changed. Everyone affected gets one email.', from: 'From', to: 'To', kinds: 'Which events', TRAINING: 'Trainings', MATCH: 'Matches', OTHER: 'Other events', action: 'What to do', CANCEL: 'Cancel', REINSTATE: 'Reinstate', SET_LOCATION: 'New place', location: 'Place', check: 'Check', preview: '{n} events are affected.', none: 'No events are affected.', confirm: 'Change {n} events', done: '{n} events changed.', tooMany: 'Too many events (at most 500). Please choose a shorter range.', invalid: 'Please check the fields (at most a year, at least one kind).', failed: 'Not possible. Please try again later.' },
  fr: { title: 'Modifier plusieurs événements', hint: 'Par exemple, annuler tous les entraînements des vacances d’automne ou les déplacer dans une autre salle. Seuls les événements qui n’ont pas commencé sont modifiés. Chaque personne concernée reçoit un seul e-mail.', from: 'Du', to: 'Au', kinds: 'Quels événements', TRAINING: 'Entraînements', MATCH: 'Matchs', OTHER: 'Autres événements', action: 'Que faire', CANCEL: 'Annuler', REINSTATE: 'Rétablir', SET_LOCATION: 'Nouveau lieu', location: 'Lieu', check: 'Vérifier', preview: '{n} événements sont concernés.', none: 'Aucun événement concerné.', confirm: 'Modifier {n} événements', done: '{n} événements modifiés.', tooMany: 'Trop d’événements (500 au plus). Veuillez choisir une période plus courte.', invalid: 'Veuillez vérifier les champs (un an au plus, au moins un type).', failed: 'Impossible. Veuillez réessayer plus tard.' },
  it: { title: 'Modifica più eventi', hint: 'Per esempio annullare tutti gli allenamenti durante le vacanze autunnali o spostarli in un’altra palestra. Vengono modificati solo gli eventi non ancora iniziati. Ogni persona interessata riceve una sola email.', from: 'Dal', to: 'Al', kinds: 'Quali eventi', TRAINING: 'Allenamenti', MATCH: 'Partite', OTHER: 'Altri eventi', action: 'Cosa fare', CANCEL: 'Annulla', REINSTATE: 'Ripristina', SET_LOCATION: 'Nuovo luogo', location: 'Luogo', check: 'Verifica', preview: '{n} eventi sono interessati.', none: 'Nessun evento interessato.', confirm: 'Modifica {n} eventi', done: '{n} eventi modificati.', tooMany: 'Troppi eventi (al massimo 500). Scegliete un periodo più breve.', invalid: 'Controllate i campi (al massimo un anno, almeno un tipo).', failed: 'Non possibile. Riprovate più tardi.' },
} satisfies Record<Language, Record<string, string>>

const fill = (text: string, values: Record<string, string | number>) =>
  text.replace(/\{(\w+)\}/g, (_, key: string) => String(values[key] ?? ''))
const kinds: EventKind[] = ['TRAINING', 'MATCH', 'OTHER']
const actions: BulkAction[] = ['CANCEL', 'REINSTATE', 'SET_LOCATION']

/** Team schedule, for its managers: one change to every matching event of a date range, checked first. */
export function BulkEventsPanel({ teamId, language, csrfToken, onChanged }: {
  teamId: string; language: Language; csrfToken: string; onChanged: () => void
}) {
  const t = words[language]
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [chosen, setChosen] = useState<Set<EventKind>>(new Set(['TRAINING']))
  const [action, setAction] = useState<BulkAction>('CANCEL')
  const [location, setLocation] = useState('')
  const [checked, setChecked] = useState<number | null>(null)
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const input = (): BulkInput => ({ teamIds: [teamId], from, to, kinds: kinds.filter(kind => chosen.has(kind)), action,
    location: action === 'SET_LOCATION' ? location.trim() || null : null })
  const reset = () => { setChecked(null); setNotice(null) }
  async function send(preview: boolean) {
    setBusy(true); setNotice(null)
    try {
      const outcome = await bulkEvents(input(), preview, csrfToken)
      if (typeof outcome === 'object') {
        if (preview) setChecked(outcome.matched)
        else { setChecked(null); setNotice({ ok: true, text: fill(t.done, { n: outcome.changed }) }); onChanged() }
      } else setNotice({ ok: false, text: outcome === 'too_many' ? t.tooMany : outcome === 'invalid' ? t.invalid : t.failed })
    } catch { setNotice({ ok: false, text: t.failed }) } finally { setBusy(false) }
  }
  return <details className="card pad">
    <summary>{t.title}</summary>
    <form className="stack" onSubmit={(event: FormEvent) => { event.preventDefault(); void send(true) }}>
      <p className="muted small">{t.hint}</p>
      <label>{t.from}<input type="date" value={from} required onChange={e => { setFrom(e.target.value); reset() }} /></label>
      <label>{t.to}<input type="date" value={to} required onChange={e => { setTo(e.target.value); reset() }} /></label>
      <fieldset><legend>{t.kinds}</legend>{kinds.map(kind => <label key={kind} className="check">
        <input type="checkbox" checked={chosen.has(kind)} onChange={() => {
          reset(); setChosen(current => { const next = new Set(current); if (next.has(kind)) next.delete(kind); else next.add(kind); return next })
        }} />{t[kind]}</label>)}</fieldset>
      <fieldset><legend>{t.action}</legend>{actions.map(item => <label key={item} className="check">
        <input type="radio" name={'bulk-action-' + teamId} checked={action === item} onChange={() => { setAction(item); reset() }} />{t[item]}</label>)}</fieldset>
      {action === 'SET_LOCATION' && <label>{t.location}<input value={location} required maxLength={200} onChange={e => { setLocation(e.target.value); reset() }} /></label>}
      <button type="submit" className="btn" disabled={busy}>{t.check}</button>
      {checked !== null && <p role="status">{checked === 0 ? t.none : fill(t.preview, { n: checked })}</p>}
      {checked !== null && checked > 0 && <button type="button" className="btn primary" disabled={busy} onClick={() => { void send(false) }}>
        {fill(t.confirm, { n: checked })}</button>}
      {notice && <p role={notice.ok ? 'status' : 'alert'}>{notice.text}</p>}
    </form>
  </details>
}
