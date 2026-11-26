import { useEffect, useState, type FormEvent } from 'react'
import { Reveal } from './Reveal'
import type { Language } from './language'
import { formatWhen, instants, localParts } from './events'
import { createShift, deleteShift, readShifts, replaceShift, volunteer, type Shift, type ShiftList, type ShiftOutcome } from './shifts'

const words = {
  de: { newShift: 'Neuer Helfereinsatz', shifts: 'Helfereinsätze', none: 'Keine Helfereinsätze.', loading: 'Wird geladen…', error: 'Helfereinsätze nicht verfügbar.', filled: 'besetzt', of: 'von', nobody: 'noch niemand', signUp: 'Eintragen', withdraw: 'Austragen', you: 'Ich', title: 'Aufgabe', date: 'Datum', start: 'Beginn', end: 'Ende', needed: 'Anzahl Personen', add: 'Einsatz hinzufügen', save: 'Speichern', cancel: 'Abbrechen', edit: 'Bearbeiten', remove: 'Löschen', confirmRemove: 'Wirklich löschen (mit allen Eintragungen)', invalid: 'Bitte Angaben prüfen: höchstens 24 Stunden und nahe beim Anlass.', denied: 'Keine Berechtigung.', closed: 'Der Einsatz hat begonnen oder der Anlass ist abgesagt.', full: 'Der Einsatz ist bereits voll.', conflict: 'Es sind mehr Personen eingetragen als Plätze.', failed: 'Nicht gespeichert. Bitte erneut versuchen.' },
  en: { newShift: 'New shift', shifts: 'Volunteer shifts', none: 'No volunteer shifts.', loading: 'Loading…', error: 'Shifts unavailable.', filled: 'filled', of: 'of', nobody: 'nobody yet', signUp: 'Sign up', withdraw: 'Withdraw', you: 'Me', title: 'Task', date: 'Date', start: 'Start', end: 'End', needed: 'People needed', add: 'Add shift', save: 'Save', cancel: 'Cancel', edit: 'Edit', remove: 'Delete', confirmRemove: 'Really delete (with all sign-ups)', invalid: 'Please check the fields: at most 24 hours and close to the event.', denied: 'No permission.', closed: 'The shift has started or the event is cancelled.', full: 'The shift is already full.', conflict: 'More people are signed up than there are places.', failed: 'Not saved. Please try again.' },
  fr: { newShift: 'Nouvelle intervention', shifts: 'Bénévolat', none: 'Aucun créneau de bénévolat.', loading: 'Chargement…', error: 'Créneaux indisponibles.', filled: 'occupé(s)', of: 'sur', nobody: 'personne pour l’instant', signUp: 'S’inscrire', withdraw: 'Se désinscrire', you: 'Moi', title: 'Tâche', date: 'Date', start: 'Début', end: 'Fin', needed: 'Personnes nécessaires', add: 'Ajouter un créneau', save: 'Enregistrer', cancel: 'Annuler', edit: 'Modifier', remove: 'Supprimer', confirmRemove: 'Vraiment supprimer (avec toutes les inscriptions)', invalid: 'Vérifiez les champs : 24 heures au plus et proche de l’événement.', denied: 'Pas d’autorisation.', closed: 'Le créneau a commencé ou l’événement est annulé.', full: 'Le créneau est déjà complet.', conflict: 'Plus de personnes sont inscrites qu’il n’y a de places.', failed: 'Non enregistré. Veuillez réessayer.' },
  it: { newShift: 'Nuovo turno', shifts: 'Turni di volontariato', none: 'Nessun turno di volontariato.', loading: 'Caricamento…', error: 'Turni non disponibili.', filled: 'occupati', of: 'su', nobody: 'ancora nessuno', signUp: 'Iscriviti', withdraw: 'Ritirati', you: 'Io', title: 'Compito', date: 'Data', start: 'Inizio', end: 'Fine', needed: 'Persone necessarie', add: 'Aggiungi turno', save: 'Salva', cancel: 'Annulla', edit: 'Modifica', remove: 'Elimina', confirmRemove: 'Eliminare davvero (con tutte le iscrizioni)', invalid: 'Controlla i campi: al massimo 24 ore e vicino all’evento.', denied: 'Nessun permesso.', closed: 'Il turno è iniziato o l’evento è annullato.', full: 'Il turno è già al completo.', conflict: 'Ci sono più persone iscritte che posti.', failed: 'Non salvato. Riprova.' },
} satisfies Record<Language, Record<string, string>>

type Draft = { title: string; date: string; start: string; end: string; needed: string }

/**
 * The volunteer shifts of one event (OpenAPI 1.16.0): the team sees each shift with its volunteers' public display
 * names, signs itself (and guardians their children) up or out, and managers add, change and delete shifts. The
 * server decides every permission; buttons only reflect what it reported.
 */
export function ShiftsPanel({ event, language, csrfToken, self }: {
  event: { id: string; title: string; startsAt: string; endsAt: string; cancelled: boolean }
  language: Language; csrfToken: string; self?: string | null
}) {
  const t = words[language]
  const blank = (): Draft => ({ title: '', ...localParts(event), needed: '2' })
  const [list, setList] = useState<ShiftList | 'denied' | 'error' | null>(null)
  const [revision, setRevision] = useState(0)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Exclude<ShiftOutcome, 'ok'> | 'failed' | null>(null)
  const [draft, setDraft] = useState<Draft>(blank)
  const [editing, setEditing] = useState<{ id: string; draft: Draft } | null>(null)
  const [deleting, setDeleting] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    readShifts(event.id).then(result => { if (active) setList(result) }, () => { if (active) setList('error') })
    return () => { active = false }
  }, [event.id, revision])

  async function run(action: () => Promise<ShiftOutcome>): Promise<boolean> {
    setBusy(true); setNotice(null)
    try {
      const outcome = await action()
      if (outcome !== 'ok') { setNotice(outcome); return false }
      return true
    } catch { setNotice('failed'); return false } finally { setBusy(false); setRevision(value => value + 1) }
  }

  function input(value: Draft) {
    const times = instants(value.date, value.start, value.end)
    const needed = Number(value.needed)
    return times && Number.isInteger(needed) ? { title: value.title.trim(), ...times, needed } : null
  }

  async function add(form: FormEvent) {
    form.preventDefault()
    const body = input(draft)
    if (!body) { setNotice('invalid'); return }
    if (await run(() => createShift(event.id, body, csrfToken))) setDraft(blank())
  }

  async function save(form: FormEvent) {
    form.preventDefault()
    if (!editing) return
    const body = input(editing.draft)
    if (!body) { setNotice('invalid'); return }
    if (await run(() => replaceShift(editing.id, body, csrfToken))) setEditing(null)
  }

  const fields = (value: Draft, change: (next: Draft) => void) => <>
    <label>{t.title}<input value={value.title} required maxLength={200} onChange={e => change({ ...value, title: e.target.value })} /></label>
    <label>{t.date}<input type="date" value={value.date} required onChange={e => change({ ...value, date: e.target.value })} /></label>
    <label>{t.start}<input type="time" value={value.start} required onChange={e => change({ ...value, start: e.target.value })} /></label>
    <label>{t.end}<input type="time" value={value.end} required onChange={e => change({ ...value, end: e.target.value })} /></label>
    <label>{t.needed}<input type="number" min={1} max={100} value={value.needed} required onChange={e => change({ ...value, needed: e.target.value })} /></label>
  </>

  if (list === 'denied') return null
  const open = (shift: Shift) => !event.cancelled && Date.parse(shift.startsAt) > Date.now()
  return (
    <section aria-label={`${t.shifts}: ${event.title}`} className="card pad stack panel">
      <h2>{t.shifts}</h2>
      {list === null && <p role="status">{t.loading}</p>}
      {list === 'error' && <p role="alert">{t.error}</p>}
      {typeof list === 'object' && list !== null && <>
        {list.items.length === 0 && <p>{t.none}</p>}
        {list.items.length > 0 && <ul className="rows shift-rows">{list.items.map(shift => <li key={shift.id} className="pad">
          {editing?.id === shift.id ? <form onSubmit={form => { void save(form) }}>
            {fields(editing.draft, next => setEditing({ id: shift.id, draft: next }))}
            <button type="submit" disabled={busy}>{t.save}</button>
            <button type="button" disabled={busy} onClick={() => setEditing(null)}>{t.cancel}</button>
          </form> : <>
            <p><strong>{shift.title}</strong> · {formatWhen(shift, language, false)} · {shift.volunteers.length} {t.of} {shift.needed} {t.filled}
            {' '}({shift.volunteers.length ? shift.volunteers.join(', ') : t.nobody})</p>
            {shift.volunteers.length >= shift.needed && !list.people.some(person => shift.signedUp.includes(person.id)) &&
              <p className="muted">{t.full}</p>}
            <div className="actions">
            {list.people.map(person => {
              const signed = shift.signedUp.includes(person.id)
              const who = person.id === self ? t.you : person.firstName
              // A full shift says so instead of offering a button that does nothing.
              if (!signed && shift.volunteers.length >= shift.needed) return null
              return <button key={person.id} type="button" aria-pressed={signed}
                disabled={busy || !open(shift)}
                onClick={() => { void run(() => volunteer(shift.id, person.id, signed, csrfToken)) }}>
                {signed ? t.withdraw : t.signUp}: {who}</button>
            })}
            {list.canManage && <>
              <button type="button" disabled={busy} onClick={() => {
                const parts = localParts(shift)
                setEditing({ id: shift.id, draft: { title: shift.title, ...parts, needed: String(shift.needed) } }); setNotice(null)
              }}>{t.edit}</button>
              {deleting === shift.id
                ? <button type="button" disabled={busy} onClick={() => { setDeleting(null); void run(() => deleteShift(shift.id, csrfToken)) }}>{t.confirmRemove}</button>
                : <button type="button" disabled={busy} onClick={() => setDeleting(shift.id)}>{t.remove}</button>}
            </>}
            </div>
          </>}
        </li>)}</ul>}
        {list.canManage && <Reveal label={t.newShift}><form aria-label={t.add} onSubmit={form => { void add(form) }}>
          {fields(draft, setDraft)}
          <button type="submit" disabled={busy}>{t.add}</button>
        </form></Reveal>}
      </>}
      {notice && <p role="alert">{t[notice]}</p>}
    </section>
  )
}
