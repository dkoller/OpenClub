import { useEffect, useState, type FormEvent } from 'react'
import type { Language } from './language'
import {
  createEvents, deleteEvent, formatWhen, instants, localParts, readTeamEvents, replaceEvent,
  type EventKind, type EventOutcome, type TeamEvent,
} from './events'

const words = {
  de: { show: 'Programm', hide: 'Programm schliessen', title: 'Programm', empty: 'Keine Termine in den nächsten acht Wochen.', more: 'Weitere Termine folgen später.', error: 'Programm nicht verfügbar.', kind: 'Art', TRAINING: 'Training', MATCH: 'Spiel', OTHER: 'Termin', name: 'Titel', date: 'Datum', start: 'Beginn', end: 'Ende', place: 'Ort', notes: 'Hinweise', repeat: 'Wöchentlich wiederholen bis', add: 'Termin hinzufügen', edit: 'Bearbeiten', save: 'Speichern', cancel: 'Abbrechen', cancelEvent: 'Absagen', reinstate: 'Wieder ansetzen', remove: 'Löschen', confirmRemove: 'Löschen bestätigen', cancelled: 'Abgesagt', invalid: 'Bitte die Angaben prüfen: Ende nach Beginn, höchstens 52 Wiederholungen.', denied: 'Keine Berechtigung für dieses Team.', gone: 'Dieser Termin existiert nicht mehr.', failed: 'Aktion fehlgeschlagen.', added: 'Termin gespeichert.' },
  en: { show: 'Schedule', hide: 'Close schedule', title: 'Schedule', empty: 'No events in the next eight weeks.', more: 'More events follow later.', error: 'Schedule unavailable.', kind: 'Kind', TRAINING: 'Training', MATCH: 'Match', OTHER: 'Event', name: 'Title', date: 'Date', start: 'Start', end: 'End', place: 'Place', notes: 'Notes', repeat: 'Repeat weekly until', add: 'Add event', edit: 'Edit', save: 'Save', cancel: 'Cancel', cancelEvent: 'Cancel event', reinstate: 'Reinstate', remove: 'Delete', confirmRemove: 'Confirm delete', cancelled: 'Cancelled', invalid: 'Please check the details: end after start, at most 52 repeats.', denied: 'No permission for this team.', gone: 'This event no longer exists.', failed: 'Action failed.', added: 'Event saved.' },
  fr: { show: 'Programme', hide: 'Fermer le programme', title: 'Programme', empty: 'Aucun événement dans les huit prochaines semaines.', more: 'D’autres événements suivent plus tard.', error: 'Programme indisponible.', kind: 'Type', TRAINING: 'Entraînement', MATCH: 'Match', OTHER: 'Événement', name: 'Titre', date: 'Date', start: 'Début', end: 'Fin', place: 'Lieu', notes: 'Remarques', repeat: 'Répéter chaque semaine jusqu’au', add: 'Ajouter un événement', edit: 'Modifier', save: 'Enregistrer', cancel: 'Annuler', cancelEvent: 'Annuler l’événement', reinstate: 'Rétablir', remove: 'Supprimer', confirmRemove: 'Confirmer la suppression', cancelled: 'Annulé', invalid: 'Vérifiez les informations : fin après le début, au plus 52 répétitions.', denied: 'Pas d’autorisation pour cette équipe.', gone: 'Cet événement n’existe plus.', failed: 'L’action a échoué.', added: 'Événement enregistré.' },
  it: { show: 'Programma', hide: 'Chiudi programma', title: 'Programma', empty: 'Nessun evento nelle prossime otto settimane.', more: 'Altri eventi seguiranno.', error: 'Programma non disponibile.', kind: 'Tipo', TRAINING: 'Allenamento', MATCH: 'Partita', OTHER: 'Evento', name: 'Titolo', date: 'Data', start: 'Inizio', end: 'Fine', place: 'Luogo', notes: 'Note', repeat: 'Ripeti ogni settimana fino al', add: 'Aggiungi evento', edit: 'Modifica', save: 'Salva', cancel: 'Annulla', cancelEvent: 'Annulla evento', reinstate: 'Ripristina', remove: 'Elimina', confirmRemove: 'Conferma eliminazione', cancelled: 'Annullato', invalid: 'Controlla i dati: fine dopo l’inizio, al massimo 52 ripetizioni.', denied: 'Nessuna autorizzazione per questa squadra.', gone: 'Questo evento non esiste più.', failed: 'Azione non riuscita.', added: 'Evento salvato.' },
} satisfies Record<Language, Record<string, string>>

type Draft = { kind: EventKind; title: string; date: string; start: string; end: string; location: string; notes: string }
const emptyDraft: Draft = { kind: 'TRAINING', title: '', date: '', start: '18:00', end: '19:30', location: '', notes: '' }
type Notice = 'invalid' | 'denied' | 'gone' | 'failed' | 'added'

function EventFields({ draft, setDraft, t }: { draft: Draft; setDraft: (draft: Draft) => void; t: typeof words.en }) {
  return <>
    <label>{t.kind}<select value={draft.kind} onChange={event => setDraft({ ...draft, kind: event.target.value as EventKind })}>
      {(['TRAINING', 'MATCH', 'OTHER'] as const).map(kind => <option key={kind} value={kind}>{t[kind]}</option>)}
    </select></label>
    <label>{t.name}<input value={draft.title} maxLength={200} required onChange={event => setDraft({ ...draft, title: event.target.value })} /></label>
    <label>{t.date}<input type="date" value={draft.date} required onChange={event => setDraft({ ...draft, date: event.target.value })} /></label>
    <label>{t.start}<input type="time" value={draft.start} required onChange={event => setDraft({ ...draft, start: event.target.value })} /></label>
    <label>{t.end}<input type="time" value={draft.end} required onChange={event => setDraft({ ...draft, end: event.target.value })} /></label>
    <label>{t.place}<input value={draft.location} maxLength={200} onChange={event => setDraft({ ...draft, location: event.target.value })} /></label>
    <label>{t.notes}<textarea value={draft.notes} maxLength={2000} onChange={event => setDraft({ ...draft, notes: event.target.value })} /></label>
  </>
}

/**
 * One team's next eight weeks (OpenAPI 1.10.0), opened on demand. Viewers see the list; managers of this team
 * (the server says so in canManage) also add, edit, cancel and delete events.
 */
export function TeamSchedule({ teamId, teamName, language, csrfToken }: {
  teamId: string; teamName: string; language: Language; csrfToken: string
}) {
  const t = words[language]
  const [open, setOpen] = useState(false)
  const [events, setEvents] = useState<TeamEvent[] | null>(null)
  const [truncated, setTruncated] = useState(false)
  const [canManage, setCanManage] = useState(false)
  const [state, setState] = useState<'idle' | 'loading' | 'ready' | 'denied' | 'error'>('idle')
  const [draft, setDraft] = useState<Draft>(emptyDraft)
  const [repeatUntil, setRepeatUntil] = useState('')
  const [editing, setEditing] = useState<{ id: string; cancelled: boolean; draft: Draft } | null>(null)
  const [deleting, setDeleting] = useState<string | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [busy, setBusy] = useState(false)
  const [revision, setRevision] = useState(0)

  useEffect(() => {
    if (!open) return
    let active = true
    setState('loading')
    readTeamEvents(teamId).then(result => {
      if (!active) return
      if (result === 'denied') { setState('denied'); return }
      setEvents(result.items); setTruncated(result.truncated); setCanManage(result.canManage); setState('ready')
    }).catch(() => { if (active) setState('error') })
    return () => { active = false }
  }, [open, teamId, revision])

  async function run(action: () => Promise<EventOutcome>, success?: Notice) {
    setBusy(true); setNotice(null)
    try {
      const outcome = await action()
      if (outcome === 'ok') { setNotice(success ?? null); setRevision(value => value + 1); return true }
      setNotice(outcome === 'invalid' ? 'invalid' : outcome === 'denied' ? 'denied' : 'gone')
      if (outcome !== 'invalid') setRevision(value => value + 1)
    } catch { setNotice('failed') } finally { setBusy(false) }
    return false
  }

  async function add(event: FormEvent) {
    event.preventDefault()
    const times = instants(draft.date, draft.start, draft.end)
    if (!times) { setNotice('invalid'); return }
    if (await run(() => createEvents(teamId, { ...draft, ...times }, repeatUntil, csrfToken), 'added')) {
      setDraft({ ...emptyDraft, kind: draft.kind, start: draft.start, end: draft.end, location: draft.location }); setRepeatUntil('')
    }
  }

  async function save(event: FormEvent) {
    event.preventDefault()
    if (!editing) return
    const times = instants(editing.draft.date, editing.draft.start, editing.draft.end)
    if (!times) { setNotice('invalid'); return }
    if (await run(() => replaceEvent(editing.id, { ...editing.draft, ...times }, editing.cancelled, csrfToken))) setEditing(null)
  }

  function draftOf(event: TeamEvent): Draft {
    return { kind: event.kind, title: event.title, ...localParts(event), location: event.location ?? '', notes: event.notes ?? '' }
  }

  return (
    <div>
      <button type="button" aria-expanded={open} onClick={() => { setOpen(!open); setNotice(null) }}>
        {open ? t.hide : t.show}
      </button>
      {open && <section aria-label={`${t.title} ${teamName}`}>
        {state === 'loading' && <p role="status">…</p>}
        {state === 'error' && <p role="alert">{t.error}</p>}
        {state === 'denied' && <p role="alert">{t.denied}</p>}
        {state === 'ready' && events?.length === 0 && <p>{t.empty}</p>}
        {state === 'ready' && events && events.length > 0 && <ul>{events.map(item => <li key={item.id}>
          {editing?.id === item.id ? <form onSubmit={event => { void save(event) }}>
            <EventFields draft={editing.draft} setDraft={next => setEditing({ ...editing, draft: next })} t={t} />
            <button type="submit" disabled={busy}>{t.save}</button>
            <button type="button" disabled={busy} onClick={() => setEditing(null)}>{t.cancel}</button>
          </form> : <>
            <strong>{formatWhen(item, language)}</strong> · {t[item.kind]}: {item.title}
            {item.location && <> · {item.location}</>}
            {item.cancelled && <> · <em>{t.cancelled}</em></>}
            {item.notes && <p>{item.notes}</p>}
            {canManage && <>
              <button type="button" disabled={busy} onClick={() => { setEditing({ id: item.id, cancelled: item.cancelled, draft: draftOf(item) }); setNotice(null) }}>{t.edit}</button>
              <button type="button" disabled={busy} onClick={() => {
                const times = { startsAt: item.startsAt, endsAt: item.endsAt }
                void run(() => replaceEvent(item.id, { ...draftOf(item), ...times }, !item.cancelled, csrfToken))
              }}>{item.cancelled ? t.reinstate : t.cancelEvent}</button>
              {deleting === item.id
                ? <button type="button" disabled={busy} onClick={() => { setDeleting(null); void run(() => deleteEvent(item.id, csrfToken)) }}>{t.confirmRemove}</button>
                : <button type="button" disabled={busy} onClick={() => setDeleting(item.id)}>{t.remove}</button>}
            </>}
          </>}
        </li>)}</ul>}
        {truncated && <p>{t.more}</p>}
        {state === 'ready' && canManage && <form onSubmit={event => { void add(event) }}>
          <EventFields draft={draft} setDraft={setDraft} t={t} />
          <label>{t.repeat}<input type="date" value={repeatUntil} min={draft.date || undefined} onChange={event => setRepeatUntil(event.target.value)} /></label>
          <button type="submit" disabled={busy}>{t.add}</button>
        </form>}
        {notice && <p role={notice === 'added' ? 'status' : 'alert'}>{t[notice]}</p>}
      </section>}
    </div>
  )
}
