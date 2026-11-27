import { useEffect, useState, type FormEvent } from 'react'
import { BackLink } from './BackLink'
import { formatDate, formatRange, weekday } from './dates'
import type { Language } from './language'
import { routeHash } from './routes'
import { byDay, changeVenue, isoWeek, readVenues, readWeek, weekStart, type PlannedBooking, type Venue } from './venues'

const words = {
  de: { all: 'Alle Plätze und Hallen', weeks: 'Woche wählen', previous: 'Vorherige Woche', next: 'Nächste Woche', thisWeek: 'Diese Woche', week: 'KW {n}', free: 'Frei', title: 'Plätze und Hallen', plan: 'Belegung der Plätze und Hallen', back: 'Termine', none: 'Der Verein hat noch keine Plätze oder Hallen erfasst.', venue: 'Platz oder Halle', error: 'Gerade nicht verfügbar.', TRAINING: 'Training', MATCH: 'Spiel', OTHER: 'Termin', add: 'Hinzufügen', name: 'Name', rename: 'Umbenennen', save: 'Speichern', cancel: 'Abbrechen', remove: 'Löschen', confirm: 'Wirklich löschen? Termine bleiben, nur ohne Platz.', duplicate: 'Diesen Namen gibt es schon.', failed: 'Nicht gespeichert.', hint: 'Termine können einen Platz oder eine Halle belegen; doppelte Buchungen werden abgelehnt. Alle Mitglieder sehen die Belegung.' },
  en: { all: 'All fields and halls', weeks: 'Choose a week', previous: 'Previous week', next: 'Next week', thisWeek: 'This week', week: 'Week {n}', free: 'Free', title: 'Fields and halls', plan: 'When fields and halls are booked', back: 'Schedule', none: 'The club has no fields or halls yet.', venue: 'Field or hall', error: 'Unavailable right now.', TRAINING: 'Training', MATCH: 'Match', OTHER: 'Event', add: 'Add', name: 'Name', rename: 'Rename', save: 'Save', cancel: 'Cancel', remove: 'Delete', confirm: 'Delete it? Events stay, without the place.', duplicate: 'This name already exists.', failed: 'Not saved.', hint: 'Events can book a field or hall; double bookings are refused. All members see when they are booked.' },
  fr: { all: 'Tous les terrains et salles', weeks: 'Choisir une semaine', previous: 'Semaine précédente', next: 'Semaine suivante', thisWeek: 'Cette semaine', week: 'Semaine {n}', free: 'Libre', title: 'Terrains et salles', plan: 'Occupation des terrains et salles', back: 'Agenda', none: 'Le club n’a encore saisi aucun terrain ni aucune salle.', venue: 'Terrain ou salle', error: 'Indisponible pour le moment.', TRAINING: 'Entraînement', MATCH: 'Match', OTHER: 'Événement', add: 'Ajouter', name: 'Nom', rename: 'Renommer', save: 'Enregistrer', cancel: 'Annuler', remove: 'Supprimer', confirm: 'Supprimer ? Les événements restent, sans le lieu.', duplicate: 'Ce nom existe déjà.', failed: 'Non enregistré.', hint: 'Les événements peuvent réserver un terrain ou une salle ; les doubles réservations sont refusées. Tous les membres voient l’occupation.' },
  it: { all: 'Tutti i campi e le palestre', weeks: 'Scegliere una settimana', previous: 'Settimana precedente', next: 'Settimana successiva', thisWeek: 'Questa settimana', week: 'Settimana {n}', free: 'Libero', title: 'Campi e palestre', plan: 'Occupazione di campi e palestre', back: 'Calendario', none: 'Il club non ha ancora inserito campi o palestre.', venue: 'Campo o palestra', error: 'Non disponibile al momento.', TRAINING: 'Allenamento', MATCH: 'Partita', OTHER: 'Evento', add: 'Aggiungi', name: 'Nome', rename: 'Rinomina', save: 'Salva', cancel: 'Annulla', remove: 'Elimina', confirm: 'Eliminare? Gli eventi restano, senza il luogo.', duplicate: 'Questo nome esiste già.', failed: 'Non salvato.', hint: 'Gli eventi possono prenotare un campo o una palestra; le doppie prenotazioni vengono rifiutate. Tutti i membri vedono l’occupazione.' },
} satisfies Record<Language, Record<string, string>>


/**
 * Schedule → fields and halls: the week plan (Platzbelegung). Every day of one week, with what is booked on each
 * field or hall by team, kind and title (never names), for all of them or one. Double bookings are refused when an
 * event is saved, so the plan shows what is free.
 */
export function VenuesPage({ language }: { language: Language }) {
  const t = words[language]
  const [venues, setVenues] = useState<Venue[] | 'error' | null>(null)
  const [chosen, setChosen] = useState('')
  const [week, setWeek] = useState(0)
  const [bookings, setBookings] = useState<PlannedBooking[] | 'error' | null>(null)
  const monday = weekStart(new Date(), week)
  const mondayKey = monday.toDateString()
  useEffect(() => {
    let active = true
    readVenues().then(result => { if (active) setVenues(result === 'denied' ? [] : result.items) })
      .catch(() => { if (active) setVenues('error') })
    return () => { active = false }
  }, [])
  useEffect(() => {
    if (!Array.isArray(venues) || venues.length === 0) return
    let active = true
    setBookings(null)
    const shown = chosen ? venues.filter(venue => venue.id === chosen) : venues
    readWeek(shown, weekStart(new Date(), week))
      .then(result => { if (active) setBookings(result === 'denied' ? [] : result) })
      .catch(() => { if (active) setBookings('error') })
    return () => { active = false }
  }, [venues, chosen, week])
  const sunday = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + 6)
  const today = new Date().toDateString()
  const dayMonth = (day: Date) => formatDate(day).slice(0, 6)
  return <section aria-labelledby="venues-title" className="stack">
    <BackLink href={routeHash({ page: 'schedule' })} label={t.back} />
    <h1 id="venues-title">{t.plan}</h1>
    {venues === null && <p role="status">…</p>}
    {venues === 'error' && <p role="alert">{t.error}</p>}
    {Array.isArray(venues) && venues.length === 0 && <p className="muted">{t.none}</p>}
    {Array.isArray(venues) && venues.length > 0 && <>
      <label>{t.venue}<select value={chosen} onChange={event => setChosen(event.target.value)}>
        <option value="">{t.all}</option>
        {venues.map(venue => <option key={venue.id} value={venue.id}>{venue.name}</option>)}</select></label>
      <nav className="week-nav" aria-label={t.weeks}>
        <button type="button" className="btn" onClick={() => setWeek(value => value - 1)} aria-label={t.previous}>‹</button>
        <h2 aria-live="polite">{t.week.replace('{n}', String(isoWeek(monday)))}<span className="muted"> {dayMonth(monday)}–{formatDate(sunday)}</span></h2>
        <button type="button" className="btn" onClick={() => setWeek(value => value + 1)} aria-label={t.next}>›</button>
        {week !== 0 && <button type="button" className="quiet" onClick={() => setWeek(0)}>{t.thisWeek}</button>}
      </nav>
      {bookings === null && <p role="status">…</p>}
      {bookings === 'error' && <p role="alert">{t.error}</p>}
      {Array.isArray(bookings) && <ol className="week-plan" key={mondayKey}>
        {byDay(monday, bookings).map(({ day, items }) => <li key={day.toDateString()}
          className={'card' + (day.toDateString() === today ? ' today' : '')}
          aria-current={day.toDateString() === today ? 'date' : undefined}>
          <h3>{weekday(day, language)} {dayMonth(day)}</h3>
          {items.length === 0 ? <p className="muted">{t.free}</p> : <ul className="rows">
            {items.map(booking => <li key={booking.eventId + booking.venueName} className="row booking">
              <b>{formatRange(booking.startsAt, booking.endsAt, language, false)}</b>
              <span className="event-text">{!chosen && <span className="venue-name">{booking.venueName}</span>}
                <span>{booking.teamName} · {t[booking.kind]}{booking.title !== t[booking.kind] ? ' · ' + booking.title : ''}</span></span>
            </li>)}
          </ul>}
        </li>)}
      </ol>}
    </>}
  </section>
}

/** Administration → fields and halls, for MANAGE_VENUES holders. */
export function VenueAdmin({ language, csrfToken }: { language: Language; csrfToken: string }) {
  const t = words[language]
  const [venues, setVenues] = useState<Venue[] | 'error' | null>(null)
  const [name, setName] = useState('')
  const [editing, setEditing] = useState<{ id: string; name: string } | null>(null)
  const [deleting, setDeleting] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    readVenues().then(result => { if (active) setVenues(result === 'denied' ? [] : result.items) })
      .catch(() => { if (active) setVenues('error') })
    return () => { active = false }
  }, [revision])
  async function run(change: () => ReturnType<typeof changeVenue>) {
    setBusy(true); setNotice(null)
    try {
      const outcome = await change()
      if (outcome === 'ok') { setRevision(value => value + 1); return true }
      setNotice(outcome === 'duplicate' ? t.duplicate : t.failed)
    } catch { setNotice(t.failed) } finally { setBusy(false) }
    return false
  }
  async function add(event: FormEvent) {
    event.preventDefault()
    if (await run(() => changeVenue('POST', null, name.trim(), csrfToken))) setName('')
  }
  return <section className="stack" aria-label={t.title}>
    <p className="muted small">{t.hint}</p>
    {venues === null && <p role="status">…</p>}
    {venues === 'error' && <p role="alert">{t.error}</p>}
    {Array.isArray(venues) && <ul className="rows card">{venues.map(venue => <li key={venue.id} className="row">
      {editing?.id === venue.id ? <form className="actions" onSubmit={event => { event.preventDefault(); void run(() => changeVenue('PUT', venue.id, editing.name.trim(), csrfToken)).then(ok => { if (ok) setEditing(null) }) }}>
        <label>{t.name}<input value={editing.name} required maxLength={100} onChange={e => setEditing({ id: venue.id, name: e.target.value })} /></label>
        <button type="submit" className="btn primary" disabled={busy}>{t.save}</button>
        <button type="button" className="btn" onClick={() => setEditing(null)}>{t.cancel}</button>
      </form> : <>
        <span className="event-text"><b>{venue.name}</b></span>
        <span className="actions">
          <button type="button" className="btn" disabled={busy} onClick={() => setEditing({ id: venue.id, name: venue.name })}>{t.rename}</button>
          {deleting === venue.id
            ? <button type="button" className="btn" disabled={busy} onClick={() => { setDeleting(null); void run(() => changeVenue('DELETE', venue.id, null, csrfToken)) }}>{t.confirm}</button>
            : <button type="button" className="danger-quiet" disabled={busy} onClick={() => setDeleting(venue.id)}>{t.remove}</button>}
        </span>
      </>}
    </li>)}</ul>}
    <form className="form-grid" onSubmit={event => { void add(event) }}>
      <label>{t.name}<input value={name} required maxLength={100} onChange={e => setName(e.target.value)} /></label>
      <button type="submit" className="btn primary" disabled={busy}>{t.add}</button>
    </form>
    {notice && <p role="alert">{notice}</p>}
  </section>
}
