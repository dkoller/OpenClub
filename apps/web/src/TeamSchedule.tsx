import { BulkEventsPanel } from './BulkEventsPanel'
import { Reveal } from './Reveal'
import { formatDayTime, formatRange } from './dates'
import { fill } from './EventBits'
import { readBookings, readVenues, type Booking, type Venue } from './venues'
import { useEffect, useState, type FormEvent } from 'react'
import type { Language } from './language'
import { routeHash } from './routes'
import { DateBlock } from './EventBits'
import {
  createEvents, deleteEvent, formatWhen, instants, localParts, readTeamEvents, replaceEvent,
  type EventKind, type EventOutcome, type TeamEvent,
} from './events'

const words = {
  de: { answersUntil: 'Antworten bis (freiwillig)', bookedShort: 'belegt', bookedHint: '{venue} ist dann belegt: {team}, {when}. Andere Zeit oder anderen Platz wählen.', newEvent: 'Neuer Termin', showPast: 'Vergangene Termine zeigen', hidePast: 'Nur kommende Termine', venue: 'Platz oder Halle', noVenue: 'keiner', taken: '{venue} ist dann schon belegt: {team}, {title}, {when}. Bitte andere Zeit oder anderen Platz wählen.', title: 'Programm', empty: 'Keine Termine in den nächsten acht Wochen.', more: 'Weitere Termine folgen später.', error: 'Programm nicht verfügbar.', kind: 'Art', TRAINING: 'Training', MATCH: 'Spiel', OTHER: 'Termin', name: 'Titel', date: 'Datum', start: 'Beginn', end: 'Ende', place: 'Ort', notes: 'Hinweise', repeat: 'Wöchentlich wiederholen bis', add: 'Termin hinzufügen', edit: 'Bearbeiten', save: 'Speichern', cancel: 'Abbrechen', cancelEvent: 'Absagen', reinstate: 'Wieder ansetzen', remove: 'Löschen', confirmRemove: 'Löschen bestätigen', cancelled: 'Abgesagt', invalid: 'Bitte die Angaben prüfen: Ende nach Beginn, höchstens 52 Wiederholungen.', denied: 'Keine Berechtigung für dieses Team.', gone: 'Dieser Termin existiert nicht mehr.', failed: 'Aktion fehlgeschlagen.', added: 'Termin gespeichert.', answers: 'Antworten', YES: 'Ja', NO: 'Nein', MAYBE: 'Vielleicht' },
  en: { answersUntil: 'Answers until (optional)', bookedShort: 'booked', bookedHint: '{venue} is booked then: {team}, {when}. Choose another time or place.', newEvent: 'New event', showPast: 'Show past events', hidePast: 'Only coming events', venue: 'Field or hall', noVenue: 'none', taken: '{venue} is already booked then: {team}, {title}, {when}. Please choose another time or place.', title: 'Schedule', empty: 'No events in the next eight weeks.', more: 'More events follow later.', error: 'Schedule unavailable.', kind: 'Kind', TRAINING: 'Training', MATCH: 'Match', OTHER: 'Event', name: 'Title', date: 'Date', start: 'Start', end: 'End', place: 'Place', notes: 'Notes', repeat: 'Repeat weekly until', add: 'Add event', edit: 'Edit', save: 'Save', cancel: 'Cancel', cancelEvent: 'Cancel event', reinstate: 'Reinstate', remove: 'Delete', confirmRemove: 'Confirm delete', cancelled: 'Cancelled', invalid: 'Please check the details: end after start, at most 52 repeats.', denied: 'No permission for this team.', gone: 'This event no longer exists.', failed: 'Action failed.', added: 'Event saved.', answers: 'Answers', YES: 'Yes', NO: 'No', MAYBE: 'Maybe' },
  fr: { answersUntil: 'Réponses jusqu’au (facultatif)', bookedShort: 'réservé', bookedHint: '{venue} est réservé à ce moment : {team}, {when}. Choisissez une autre heure ou un autre lieu.', newEvent: 'Nouvel événement', showPast: 'Afficher les événements passés', hidePast: 'Seulement les prochains', venue: 'Terrain ou salle', noVenue: 'aucun', taken: '{venue} est déjà réservé à ce moment : {team}, {title}, {when}. Veuillez choisir une autre heure ou un autre lieu.', title: 'Programme', empty: 'Aucun événement dans les huit prochaines semaines.', more: 'D’autres événements suivent plus tard.', error: 'Programme indisponible.', kind: 'Type', TRAINING: 'Entraînement', MATCH: 'Match', OTHER: 'Événement', name: 'Titre', date: 'Date', start: 'Début', end: 'Fin', place: 'Lieu', notes: 'Remarques', repeat: 'Répéter chaque semaine jusqu’au', add: 'Ajouter un événement', edit: 'Modifier', save: 'Enregistrer', cancel: 'Annuler', cancelEvent: 'Annuler l’événement', reinstate: 'Rétablir', remove: 'Supprimer', confirmRemove: 'Confirmer la suppression', cancelled: 'Annulé', invalid: 'Vérifiez les informations : fin après le début, au plus 52 répétitions.', denied: 'Pas d’autorisation pour cette équipe.', gone: 'Cet événement n’existe plus.', failed: 'L’action a échoué.', added: 'Événement enregistré.', answers: 'Réponses', YES: 'Oui', NO: 'Non', MAYBE: 'Peut-être' },
  it: { answersUntil: 'Risposte fino a (facoltativo)', bookedShort: 'prenotato', bookedHint: '{venue} è prenotato in quel momento: {team}, {when}. Scegliete un altro orario o luogo.', newEvent: 'Nuovo evento', showPast: 'Mostra eventi passati', hidePast: 'Solo i prossimi', venue: 'Campo o palestra', noVenue: 'nessuno', taken: '{venue} è già prenotato in quel momento: {team}, {title}, {when}. Scegliete un altro orario o luogo.', title: 'Programma', empty: 'Nessun evento nelle prossime otto settimane.', more: 'Altri eventi seguiranno.', error: 'Programma non disponibile.', kind: 'Tipo', TRAINING: 'Allenamento', MATCH: 'Partita', OTHER: 'Evento', name: 'Titolo', date: 'Data', start: 'Inizio', end: 'Fine', place: 'Luogo', notes: 'Note', repeat: 'Ripeti ogni settimana fino al', add: 'Aggiungi evento', edit: 'Modifica', save: 'Salva', cancel: 'Annulla', cancelEvent: 'Annulla evento', reinstate: 'Ripristina', remove: 'Elimina', confirmRemove: 'Conferma eliminazione', cancelled: 'Annullato', invalid: 'Controlla i dati: fine dopo l’inizio, al massimo 52 ripetizioni.', denied: 'Nessuna autorizzazione per questa squadra.', gone: 'Questo evento non esiste più.', failed: 'Azione non riuscita.', added: 'Evento salvato.', answers: 'Risposte', YES: 'Sì', NO: 'No', MAYBE: 'Forse' },
} satisfies Record<Language, Record<string, string>>

/** `answersUntil`: a local date and time ("2026-12-03T20:00") or empty (OpenAPI 1.39.0). */
type Draft = { kind: EventKind; title: string; date: string; start: string; end: string; location: string; notes: string; venueId: string; answersUntil: string }
const emptyDraft: Draft = { kind: 'TRAINING', title: '', date: '', start: '18:00', end: '19:30', location: '', notes: '', venueId: '', answersUntil: '' }
const deadline = (draft: Draft) => draft.answersUntil ? new Date(draft.answersUntil).toISOString() : undefined
const pad2 = (value: number) => String(value).padStart(2, '0')
/** An instant as the value of a datetime-local input, in the browser's time zone. */
const localDateTime = (instant: string) => {
  const date = new Date(instant)
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}T${pad2(date.getHours())}:${pad2(date.getMinutes())}`
}
type Notice = 'invalid' | 'denied' | 'gone' | 'failed' | 'added' | 'taken'

/**
 * Which fields and halls are already booked at the draft's time (the first date of a series), by another event than
 * `eventId`. Read from the venues' bookings (OpenAPI 1.30.0) a moment after the time stops changing; the server still
 * refuses a double booking on save.
 */
function useClashes(draft: Draft, venues: Venue[], eventId?: string): Map<string, Booking> {
  const [clashes, setClashes] = useState(new Map<string, Booking>())
  const times = instants(draft.date, draft.start, draft.end)
  const from = times?.startsAt ?? ''
  const to = times?.endsAt ?? ''
  useEffect(() => {
    if (!from || venues.length === 0) { setClashes(new Map()); return }
    let active = true
    const timer = setTimeout(() => {
      void Promise.all(venues.map(venue => readBookings(venue.id, from, to)
        .then(items => [venue.id, items === 'denied' ? undefined : items.find(item => item.eventId !== eventId)] as const)
        .catch(() => [venue.id, undefined] as const)))
        .then(results => {
          if (!active) return
          setClashes(new Map(results.filter((entry): entry is readonly [string, Booking] => entry[1] !== undefined)))
        })
    }, 300)
    return () => { active = false; clearTimeout(timer) }
  }, [from, to, venues, eventId])
  return clashes
}

function EventFields({ draft, setDraft, t, venues, language, eventId }: {
  draft: Draft; setDraft: (draft: Draft) => void; t: typeof words.en; venues: Venue[]; language: Language; eventId?: string
}) {
  const clashes = useClashes(draft, venues, eventId)
  const chosen = draft.venueId ? clashes.get(draft.venueId) : undefined
  const venueName = venues.find(venue => venue.id === draft.venueId)?.name ?? ''
  return <>
    <label>{t.kind}<select value={draft.kind} onChange={event => setDraft({ ...draft, kind: event.target.value as EventKind })}>
      {(['TRAINING', 'MATCH', 'OTHER'] as const).map(kind => <option key={kind} value={kind}>{t[kind]}</option>)}
    </select></label>
    <label>{t.name}<input value={draft.title} maxLength={200} required onChange={event => setDraft({ ...draft, title: event.target.value })} /></label>
    <label>{t.date}<input type="date" value={draft.date} required onChange={event => setDraft({ ...draft, date: event.target.value })} /></label>
    <label>{t.start}<input type="time" value={draft.start} required onChange={event => setDraft({ ...draft, start: event.target.value })} /></label>
    <label>{t.end}<input type="time" value={draft.end} required onChange={event => setDraft({ ...draft, end: event.target.value })} /></label>
    <label>{t.place}<input value={draft.location} maxLength={200} onChange={event => setDraft({ ...draft, location: event.target.value })} /></label>
    {(venues.length > 0 || draft.venueId) && <label>{t.venue}<select value={draft.venueId} onChange={event => setDraft({ ...draft, venueId: event.target.value })}>
      <option value="">{t.noVenue}</option>
      {venues.map(venue => {
        const clash = clashes.get(venue.id)
        return <option key={venue.id} value={venue.id} disabled={clash !== undefined && venue.id !== draft.venueId}>
          {venue.name}{clash ? ` (${t.bookedShort}: ${clash.teamName} ${formatRange(clash.startsAt, clash.endsAt, language, false)})` : ''}</option>
      })}
    </select></label>}
    {chosen && <p role="status" className="open">{fill(t.bookedHint, { venue: venueName, team: chosen.teamName,
      when: formatRange(chosen.startsAt, chosen.endsAt, language, false) })}</p>}
    <label>{t.notes}<textarea value={draft.notes} maxLength={2000} onChange={event => setDraft({ ...draft, notes: event.target.value })} /></label>
    <label>{t.answersUntil}<input type="datetime-local" value={draft.answersUntil}
      max={draft.date && draft.start ? `${draft.date}T${draft.start}` : undefined}
      onChange={event => setDraft({ ...draft, answersUntil: event.target.value })} /></label>
  </>
}

/**
 * One team's next eight weeks (OpenAPI 1.10.0), each event a link to its coach page (answers, helpers, rides).
 * Managers of this team (the server says so in canManage) also add, edit, cancel and delete events here.
 */
export function TeamSchedule({ teamId, teamName, language, csrfToken }: {
  teamId: string; teamName: string; language: Language; csrfToken: string
}) {
  const t = words[language]
  const [events, setEvents] = useState<TeamEvent[] | null>(null)
  const [truncated, setTruncated] = useState(false)
  const [canManage, setCanManage] = useState(false)
  const [state, setState] = useState<'loading' | 'ready' | 'denied' | 'error'>('loading')
  const [draft, setDraft] = useState<Draft>(emptyDraft)
  const [repeatUntil, setRepeatUntil] = useState('')
  const [notice, setNotice] = useState<Notice | null>(null)
  const [busy, setBusy] = useState(false)
  const [revision, setRevision] = useState(0)
  const [past, setPast] = useState(false)
  const [venues, setVenues] = useState<Venue[]>([])
  const [taken, setTaken] = useState('')
  useEffect(() => {
    let active = true
    readVenues().then(result => { if (active && result !== 'denied') setVenues(result.items) }).catch(() => {})
    return () => { active = false }
  }, [])

  useEffect(() => {
    let active = true
    // With past events: the last eight weeks too, so a coach finds a training afterwards (who came, J+S).
    const now = Date.now(), week = 7 * 86_400_000
    const range = past ? { from: new Date(now - 8 * week), to: new Date(now + 8 * week) } : undefined
    readTeamEvents(teamId, fetch, range).then(result => {
      if (!active) return
      if (result === 'denied') { setState('denied'); return }
      setEvents(result.items); setTruncated(result.truncated); setCanManage(result.canManage); setState('ready')
    }).catch(() => { if (active) setState('error') })
    return () => { active = false }
  }, [teamId, revision, past])

  async function run(action: () => Promise<EventOutcome>, success?: Notice) {
    setBusy(true); setNotice(null)
    try {
      const outcome = await action()
      if (outcome === 'ok') { setNotice(success ?? null); setRevision(value => value + 1); return true }
      if (typeof outcome === 'object') {
        const when = formatDayTime(outcome.taken.startsAt, language)
        setTaken(fill(t.taken, { venue: t.venue, team: outcome.taken.teamName, title: outcome.taken.title, when })); setNotice('taken'); return false
      }
      setNotice(outcome === 'invalid' ? 'invalid' : outcome === 'denied' ? 'denied' : 'gone')
      if (outcome !== 'invalid') setRevision(value => value + 1)
    } catch { setNotice('failed') } finally { setBusy(false) }
    return false
  }

  async function add(event: FormEvent) {
    event.preventDefault()
    const times = instants(draft.date, draft.start, draft.end)
    if (!times) { setNotice('invalid'); return }
    if (await run(() => createEvents(teamId, { ...draft, ...times, venueId: draft.venueId || undefined, answersUntil: deadline(draft) }, repeatUntil, csrfToken), 'added')) {
      setDraft({ ...emptyDraft, kind: draft.kind, start: draft.start, end: draft.end, location: draft.location, venueId: draft.venueId }); setRepeatUntil('')
    }
  }

  return (
    <section aria-label={`${t.title} ${teamName}`} className="stack">
      {state === 'loading' && <p role="status">…</p>}
      {state === 'error' && <p role="alert">{t.error}</p>}
      {state === 'denied' && <p role="alert">{t.denied}</p>}
      {state !== 'loading' && <p><button type="button" aria-pressed={past} onClick={() => setPast(!past)}>{past ? t.hidePast : t.showPast}</button></p>}
      {state === 'ready' && events?.length === 0 && <p>{t.empty}</p>}
      {state === 'ready' && events && events.length > 0 && <ul className="rows card team-events">{events.map(item => <li key={item.id}>
        {/* Edit, cancel and delete are on the event's own page, so the list stays a list (design rule, 2026-11-26). */}
        <a className="row" href={routeHash({ page: 'team-event', teamId, eventId: item.id })}>
          <DateBlock instant={item.startsAt} language={language} />
          <span className="event-text"><b>{item.title}</b>
            <span className="muted">{item.title !== t[item.kind] ? t[item.kind] + ' · ' : ''}{formatWhen(item, language, false)}{item.location && <> · {item.location}</>}{item.venueName && <> · {item.venueName}</>}</span>
            {item.cancelled ? <span><span className="open">{t.cancelled}</span></span>
              : <span>{t.answers}: {item.counts.yes} {t.YES}, {item.counts.no} {t.NO}, {item.counts.maybe} {t.MAYBE}</span>}
          </span>
          <span className="chevron" aria-hidden="true">›</span>
        </a>
      </li>)}</ul>}
      {truncated && <p>{t.more}</p>}
      {notice && <p role={notice === 'added' ? 'status' : 'alert'}>{notice === 'taken' ? taken : t[notice]}</p>}
      {state === 'ready' && canManage && <Reveal label={t.newEvent}><form aria-labelledby="team-add-event" className="card pad team-add" onSubmit={event => { void add(event) }}>
        <h2 id="team-add-event">{t.add}</h2>
        <EventFields draft={draft} setDraft={setDraft} t={t} venues={venues} language={language} />
        <label>{t.repeat}<input type="date" value={repeatUntil} min={draft.date || undefined} onChange={event => setRepeatUntil(event.target.value)} /></label>
        <button type="submit" disabled={busy}>{t.add}</button>
      </form></Reveal>}
      {state === 'ready' && canManage && <BulkEventsPanel teamId={teamId} language={language} csrfToken={csrfToken}
        onChanged={() => setRevision(value => value + 1)} venues={venues} />}
    </section>
  )
}

const draftOf = (event: TeamEvent): Draft =>
  ({ kind: event.kind, title: event.title, ...localParts(event), location: event.location ?? '', notes: event.notes ?? '', venueId: event.venueId ?? '',
    answersUntil: event.answersUntil ? localDateTime(event.answersUntil) : '' })

/**
 * Edit, cancel or reinstate, and delete one event, on the coach's event page (only with canManage). [onChanged] reloads
 * the page; [onDeleted] leaves it.
 */
export function ManageEvent({ event, language, csrfToken, onChanged, onDeleted }: {
  event: TeamEvent; language: Language; csrfToken: string; onChanged: () => void; onDeleted: () => void
}) {
  const t = words[language]
  const [venues, setVenues] = useState<Venue[]>([])
  const [editing, setEditing] = useState<Draft | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  useEffect(() => {
    let active = true
    readVenues().then(result => { if (active && result !== 'denied') setVenues(result.items) }).catch(() => {})
    return () => { active = false }
  }, [])

  async function run(action: () => Promise<EventOutcome>, after: () => void) {
    setBusy(true); setNotice(null)
    try {
      const outcome = await action()
      if (outcome === 'ok') { after(); return }
      if (typeof outcome === 'object') {
        setNotice(fill(t.taken, { venue: t.venue, team: outcome.taken.teamName, title: outcome.taken.title,
          when: formatDayTime(outcome.taken.startsAt, language) }))
        return
      }
      setNotice(outcome === 'invalid' ? t.invalid : outcome === 'denied' ? t.denied : t.gone)
    } catch { setNotice(t.failed) } finally { setBusy(false) }
  }

  async function save(form: FormEvent) {
    form.preventDefault()
    if (!editing) return
    const times = instants(editing.date, editing.start, editing.end)
    if (!times) { setNotice(t.invalid); return }
    await run(() => replaceEvent(event.id, { ...editing, ...times, venueId: editing.venueId || undefined, answersUntil: deadline(editing) }, event.cancelled, csrfToken),
      () => { setEditing(null); onChanged() })
  }

  return <section aria-label={t.edit} className="stack">
    {editing ? <form className="card pad" onSubmit={form => { void save(form) }}>
      <EventFields draft={editing} setDraft={setEditing} t={t} venues={venues} language={language} eventId={event.id} />
      <div className="actions">
        <button type="submit" disabled={busy}>{t.save}</button>
        <button type="button" disabled={busy} onClick={() => setEditing(null)}>{t.cancel}</button>
      </div>
    </form> : <div className="actions">
      <button type="button" disabled={busy} onClick={() => { setEditing(draftOf(event)); setNotice(null) }}>{t.edit}</button>
      <button type="button" disabled={busy} onClick={() => {
        void run(() => replaceEvent(event.id, { ...draftOf(event), startsAt: event.startsAt, endsAt: event.endsAt, venueId: event.venueId ?? undefined, answersUntil: event.answersUntil ?? undefined },
          !event.cancelled, csrfToken), onChanged)
      }}>{event.cancelled ? t.reinstate : t.cancelEvent}</button>
      {deleting
        ? <button type="button" disabled={busy} onClick={() => { setDeleting(false); void run(() => deleteEvent(event.id, csrfToken), onDeleted) }}>{t.confirmRemove}</button>
        : <button type="button" disabled={busy} onClick={() => setDeleting(true)}>{t.remove}</button>}
    </div>}
    {notice && <p role="alert">{notice}</p>}
  </section>
}
