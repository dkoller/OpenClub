import { useEffect, useState, type FormEvent } from 'react'
import { BackLink } from './BackLink'
import { formatRange } from './dates'
import type { Language } from './language'
import { routeHash } from './routes'
import { changeVenue, readBookings, readVenues, type Booking, type Venue } from './venues'

const words = {
  de: { title: 'Plätze und Hallen', back: 'Termine', none: 'Der Verein hat noch keine Plätze oder Hallen erfasst.', venue: 'Platz oder Halle', free: 'In den nächsten 14 Tagen nicht gebucht.', error: 'Gerade nicht verfügbar.', TRAINING: 'Training', MATCH: 'Spiel', OTHER: 'Termin', add: 'Hinzufügen', name: 'Name', rename: 'Umbenennen', save: 'Speichern', cancel: 'Abbrechen', remove: 'Löschen', confirm: 'Wirklich löschen? Termine bleiben, nur ohne Platz.', duplicate: 'Diesen Namen gibt es schon.', failed: 'Nicht gespeichert.', hint: 'Termine können einen Platz oder eine Halle belegen; doppelte Buchungen werden abgelehnt. Alle Mitglieder sehen die Belegung.' },
  en: { title: 'Fields and halls', back: 'Schedule', none: 'The club has no fields or halls yet.', venue: 'Field or hall', free: 'Not booked in the next 14 days.', error: 'Unavailable right now.', TRAINING: 'Training', MATCH: 'Match', OTHER: 'Event', add: 'Add', name: 'Name', rename: 'Rename', save: 'Save', cancel: 'Cancel', remove: 'Delete', confirm: 'Delete it? Events stay, without the place.', duplicate: 'This name already exists.', failed: 'Not saved.', hint: 'Events can book a field or hall; double bookings are refused. All members see when they are booked.' },
  fr: { title: 'Terrains et salles', back: 'Agenda', none: 'Le club n’a encore saisi aucun terrain ni aucune salle.', venue: 'Terrain ou salle', free: 'Pas réservé ces 14 prochains jours.', error: 'Indisponible pour le moment.', TRAINING: 'Entraînement', MATCH: 'Match', OTHER: 'Événement', add: 'Ajouter', name: 'Nom', rename: 'Renommer', save: 'Enregistrer', cancel: 'Annuler', remove: 'Supprimer', confirm: 'Supprimer ? Les événements restent, sans le lieu.', duplicate: 'Ce nom existe déjà.', failed: 'Non enregistré.', hint: 'Les événements peuvent réserver un terrain ou une salle ; les doubles réservations sont refusées. Tous les membres voient l’occupation.' },
  it: { title: 'Campi e palestre', back: 'Calendario', none: 'Il club non ha ancora inserito campi o palestre.', venue: 'Campo o palestra', free: 'Non prenotato nei prossimi 14 giorni.', error: 'Non disponibile al momento.', TRAINING: 'Allenamento', MATCH: 'Partita', OTHER: 'Evento', add: 'Aggiungi', name: 'Nome', rename: 'Rinomina', save: 'Salva', cancel: 'Annulla', remove: 'Elimina', confirm: 'Eliminare? Gli eventi restano, senza il luogo.', duplicate: 'Questo nome esiste già.', failed: 'Non salvato.', hint: 'Gli eventi possono prenotare un campo o una palestra; le doppie prenotazioni vengono rifiutate. Tutti i membri vedono l’occupazione.' },
} satisfies Record<Language, Record<string, string>>


/** Schedule → fields and halls: the next 14 days of one venue's bookings, by team and title (never names). */
export function VenuesPage({ language }: { language: Language }) {
  const t = words[language]
  const [venues, setVenues] = useState<Venue[] | 'error' | null>(null)
  const [chosen, setChosen] = useState('')
  const [bookings, setBookings] = useState<Booking[] | 'error' | null>(null)
  useEffect(() => {
    let active = true
    readVenues().then(result => {
      if (!active) return
      const items = result === 'denied' ? [] : result.items
      setVenues(items); if (items[0]) setChosen(items[0].id)
    }).catch(() => { if (active) setVenues('error') })
    return () => { active = false }
  }, [])
  useEffect(() => {
    if (!chosen) return
    let active = true
    setBookings(null)
    const from = new Date()
    const to = new Date(from.getTime() + 14 * 86400000)
    readBookings(chosen, from.toISOString(), to.toISOString())
      .then(result => { if (active) setBookings(result === 'denied' ? [] : result) })
      .catch(() => { if (active) setBookings('error') })
    return () => { active = false }
  }, [chosen])
  const when = (booking: Booking) => formatRange(booking.startsAt, booking.endsAt, language)
  return <section aria-labelledby="venues-title" className="stack">
    <BackLink href={routeHash({ page: 'schedule' })} label={t.back} />
    <h1 id="venues-title">{t.title}</h1>
    {venues === null && <p role="status">…</p>}
    {venues === 'error' && <p role="alert">{t.error}</p>}
    {Array.isArray(venues) && venues.length === 0 && <p className="muted">{t.none}</p>}
    {Array.isArray(venues) && venues.length > 0 && <>
      <label>{t.venue}<select value={chosen} onChange={event => setChosen(event.target.value)}>
        {venues.map(venue => <option key={venue.id} value={venue.id}>{venue.name}</option>)}</select></label>
      {bookings === null && <p role="status">…</p>}
      {bookings === 'error' && <p role="alert">{t.error}</p>}
      {Array.isArray(bookings) && (bookings.length === 0 ? <p className="muted">{t.free}</p> : <ul className="rows card">
        {bookings.map(booking => <li key={booking.eventId} className="row"><span className="event-text">
          <b>{when(booking)}</b><span>{booking.teamName} · {t[booking.kind]} · {booking.title}</span></span></li>)}
      </ul>)}
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
