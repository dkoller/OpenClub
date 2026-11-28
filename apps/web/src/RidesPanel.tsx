import { formatPhone } from './phone'
import { useEffect, useState, type FormEvent } from 'react'
import { Reveal } from './Reveal'
import { formatDayTime } from './dates'
import type { Language } from './language'
import { localParts } from './events'
import { changeRide, offerRide, readRides, requestRide, seat, withdrawRide, type Direction, type Ride, type RideList, type RideOutcome } from './rides'

const words = {
  de: { needs: 'Sucht eine Mitfahrt', needTitle: 'Mitfahrt gesucht', ask: 'Mitfahrt suchen', askFor: 'Für', unask: 'Nicht mehr suchen', needsHint: 'Wer schon fährt, bekommt beim ersten Gesuch eine Nachricht, ohne Namen. Mit einem gebuchten Platz ist das Gesuch erledigt.', seatsLabel: 'Plätze', rides: 'Fahrgemeinschaften', none: 'Noch keine Mitfahrgelegenheit.', THERE: 'Hinfahrt', BACK: 'Rückfahrt', BOTH: 'Hin und zurück', departs: 'Abfahrt', at: 'Treffpunkt', of: 'von', seats: 'Plätze', nobody: 'noch niemand', book: 'Platz buchen', cancel: 'Platz freigeben', you: 'Ich', driverPhone: 'Telefon Fahrer/in', phone: 'Tel.', offer: 'Fahrt anbieten', save: 'Änderungen speichern', withdraw: 'Fahrt zurückziehen', direction: 'Richtung', date: 'Datum', time: 'Zeit', point: 'Treffpunkt', invalid: 'Bitte Angaben prüfen: Abfahrt zwischen einem Tag vorher und dem Ende des Termins.', denied: 'Keine Berechtigung.', exists: 'Sie bieten für diesen Termin bereits eine Fahrt an.', closed: 'Die Fahrt ist bereits abgefahren oder der Termin abgesagt.', full: 'Kein Platz mehr frei.', conflict: 'Es sind mehr Plätze gebucht.', failed: 'Nicht gespeichert. Bitte erneut versuchen.', phones: 'Fahrer/in und Mitfahrende sehen gegenseitig ihre Telefonnummer.' },
  en: { needs: 'Needs a ride', needTitle: 'Rides wanted', ask: 'Ask for a ride', askFor: 'For', unask: 'No longer needed', needsHint: 'Drivers get one message for the first request, without a name. A booked seat ends the request.', seatsLabel: 'Seats', rides: 'Carpooling', none: 'No rides offered yet.', THERE: 'There', BACK: 'Back', BOTH: 'There and back', departs: 'Departs', at: 'Meeting point', of: 'of', seats: 'seats', nobody: 'nobody yet', book: 'Book seat', cancel: 'Give up seat', you: 'Me', driverPhone: 'Driver’s phone', phone: 'Phone', offer: 'Offer a ride', save: 'Save changes', withdraw: 'Withdraw ride', direction: 'Direction', date: 'Date', time: 'Time', point: 'Meeting point', invalid: 'Please check the fields: departure between a day before and the end of the event.', denied: 'No permission.', exists: 'You already offer a ride to this event.', closed: 'The ride has left or the event is cancelled.', full: 'No seat left.', conflict: 'More seats are booked.', failed: 'Not saved. Please try again.', phones: 'Drivers and their passengers see each other’s phone number.' },
  fr: { needs: 'Cherche une place', needTitle: 'Places recherchées', ask: 'Chercher une place', askFor: 'Pour', unask: 'Plus nécessaire', needsHint: 'Les conducteurs reçoivent un message à la première demande, sans nom. Une place réservée clôt la demande.', seatsLabel: 'Places', rides: 'Covoiturage', none: 'Aucun trajet proposé.', THERE: 'Aller', BACK: 'Retour', BOTH: 'Aller-retour', departs: 'Départ', at: 'Rendez-vous', of: 'sur', seats: 'places', nobody: 'personne pour l’instant', book: 'Réserver une place', cancel: 'Libérer la place', you: 'Moi', driverPhone: 'Téléphone du conducteur', phone: 'Tél.', offer: 'Proposer un trajet', save: 'Enregistrer', withdraw: 'Retirer le trajet', direction: 'Sens', date: 'Date', time: 'Heure', point: 'Rendez-vous', invalid: 'Vérifiez les champs : départ entre la veille et la fin de l’événement.', denied: 'Pas d’autorisation.', exists: 'Vous proposez déjà un trajet pour cet événement.', closed: 'Le trajet est parti ou l’événement est annulé.', full: 'Plus de place libre.', conflict: 'Plus de places sont réservées.', failed: 'Non enregistré. Veuillez réessayer.', phones: 'Conducteurs et passagers voient mutuellement leur numéro de téléphone.' },
  it: { needs: 'Cerca un passaggio', needTitle: 'Passaggi cercati', ask: 'Cerca un passaggio', askFor: 'Per', unask: 'Non serve più', needsHint: 'Chi guida riceve un messaggio alla prima richiesta, senza nome. Un posto prenotato chiude la richiesta.', seatsLabel: 'Posti', rides: 'Car pooling', none: 'Nessun passaggio offerto.', THERE: 'Andata', BACK: 'Ritorno', BOTH: 'Andata e ritorno', departs: 'Partenza', at: 'Ritrovo', of: 'su', seats: 'posti', nobody: 'ancora nessuno', book: 'Prenota posto', cancel: 'Libera posto', you: 'Io', driverPhone: 'Telefono autista', phone: 'Tel.', offer: 'Offri un passaggio', save: 'Salva modifiche', withdraw: 'Ritira passaggio', direction: 'Direzione', date: 'Data', time: 'Ora', point: 'Ritrovo', invalid: 'Controlla i campi: partenza tra il giorno prima e la fine dell’evento.', denied: 'Nessun permesso.', exists: 'Offri già un passaggio per questo evento.', closed: 'Il passaggio è partito o l’evento è annullato.', full: 'Nessun posto libero.', conflict: 'Ci sono più posti prenotati.', failed: 'Non salvato. Riprova.', phones: 'Autisti e passeggeri vedono reciprocamente il numero di telefono.' },
} satisfies Record<Language, Record<string, string>>

type Draft = { direction: Direction; seats: string; point: string; date: string; time: string }

/** Rides to one event (OpenAPI 1.21.0); the server decides who may offer, book and see phones. */
export function RidesPanel({ event, language, csrfToken, self }: {
  event: { id: string; title: string; startsAt: string; endsAt: string; cancelled: boolean }
  language: Language; csrfToken: string; self?: string | null
}) {
  const t = words[language]
  const parts = localParts(event)
  const blank = (): Draft => ({ direction: 'BOTH', seats: '3', point: '', date: parts.date, time: parts.start })
  const [list, setList] = useState<RideList | 'denied' | 'error' | null>(null)
  const [revision, setRevision] = useState(0)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Exclude<RideOutcome, 'ok'> | 'failed' | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [asker, setAsker] = useState('')
  const [askDirection, setAskDirection] = useState<Direction>('BOTH')

  useEffect(() => {
    let active = true
    readRides(event.id).then(result => { if (active) setList(result) }, () => { if (active) setList('error') })
    return () => { active = false }
  }, [event.id, revision])

  async function run(action: () => Promise<RideOutcome>) {
    setBusy(true); setNotice(null)
    try {
      const outcome = await action()
      if (outcome !== 'ok') setNotice(outcome)
      return outcome === 'ok'
    } catch { setNotice('failed'); return false } finally { setBusy(false); setRevision(value => value + 1) }
  }

  if (list === 'denied') return null
  if (list === null) return <p role="status">…</p>
  if (list === 'error') return <p role="alert">{t.failed}</p>
  const own = list.items.find(ride => ride.mine)
  const current = draft ?? (own ? fromRide(own) : blank())

  async function submit(form: FormEvent) {
    form.preventDefault()
    const departs = new Date(`${current.date}T${current.time}`)
    if (Number.isNaN(departs.getTime())) { setNotice('invalid'); return }
    const input = { direction: current.direction, seats: Number(current.seats), meetingPoint: current.point.trim(), departsAt: departs.toISOString() }
    if (await run(() => own ? changeRide(own.id, input, csrfToken) : offerRide(event.id, input, csrfToken))) setDraft(null)
  }

  const open = (ride: Ride) => !event.cancelled && Date.parse(ride.departsAt) > Date.now()
  const startsLater = !event.cancelled && Date.parse(event.startsAt) > Date.now()
  // The caller and their children in the team who have neither a request nor a seat yet.
  const askable = startsLater ? list.people.filter(person => !list.requests.some(item => item.memberId === person.id) &&
    !list.items.some(ride => ride.booked.includes(person.id))) : []
  return (
    <section aria-label={`${t.rides}: ${event.title}`} className="card pad stack panel">
      <h2>{t.rides}</h2>
      {list.items.length === 0 && <p>{t.none}</p>}
      {list.items.length > 0 && <ul className="rows shift-rows">{list.items.map(ride => <li key={ride.id} className="pad-y">
        <strong>{ride.driver}</strong> · {t[ride.direction]} · {t.departs} {formatDayTime(ride.departsAt, language)} · {t.at}: {ride.meetingPoint}
        {' '}· {ride.passengers.length} {t.of} {ride.seats} {t.seats}
        {' '}({ride.passengers.length ? ride.passengers.map(p => p.phone ? `${p.displayName} (${t.phone} ${formatPhone(p.phone)})` : p.displayName).join(', ') : t.nobody})
        {ride.driverPhone && <> · {t.driverPhone}: <a href={`tel:${ride.driverPhone.replace(/[^+0-9]/g, '')}`}>{formatPhone(ride.driverPhone)}</a></>}
        {list.people.map(person => {
          const booked = ride.booked.includes(person.id)
          return <button key={person.id} type="button" aria-pressed={booked}
            disabled={busy || !open(ride) || (!booked && ride.passengers.length >= ride.seats)}
            onClick={() => { void run(() => seat(ride.id, person.id, booked, csrfToken)) }}>
            {booked ? t.cancel : t.book}: {person.id === self ? t.you : person.firstName}</button>
        })}
        {ride.mine && <button type="button" disabled={busy} onClick={() => { void run(() => withdrawRide(ride.id, csrfToken)) }}>{t.withdraw}</button>}
      </li>)}</ul>}
      {/* Ride requests (1.41.0): who needs a seat, and asking for oneself or a child until the event starts. */}
      {list.requests.length > 0 && <>
        <h3>{t.needTitle}</h3>
        <ul className="rows shift-rows">{list.requests.map((item, index) => <li key={index} className="pad-y">
          {item.displayName} · {t[item.direction]}
          {item.memberId && startsLater && <> <button type="button" className="quiet" disabled={busy}
            onClick={() => { void run(() => requestRide(event.id, item.memberId!, null, csrfToken)) }}>{t.unask}</button></>}
        </li>)}</ul>
      </>}
      {askable.length > 0 && <Reveal label={t.ask} quiet><form className="form-grid" aria-label={t.ask} onSubmit={form => {
        form.preventDefault(); void run(() => requestRide(event.id, asker || askable[0].id, askDirection, csrfToken))
      }}>
        {askable.length > 1 && <label>{t.askFor}<select value={asker || askable[0].id} onChange={e => setAsker(e.target.value)}>
          {askable.map(person => <option key={person.id} value={person.id}>{person.id === self ? t.you : person.firstName}</option>)}
        </select></label>}
        <label>{t.direction}<select value={askDirection} onChange={e => setAskDirection(e.target.value as Direction)}>
          {(['THERE', 'BACK', 'BOTH'] as const).map(d => <option key={d} value={d}>{t[d]}</option>)}
        </select></label>
        <p className="muted small">{t.needsHint}</p>
        <button type="submit" disabled={busy}>{t.ask}</button>
      </form></Reveal>}
      {list.canOffer && !event.cancelled && <Reveal label={t.offer} open={own !== undefined}><form aria-label={own ? t.save : t.offer} onSubmit={form => { void submit(form) }}>
        <label>{t.direction}<select value={current.direction} onChange={e => setDraft({ ...current, direction: e.target.value as Direction })}>
          {(['THERE', 'BACK', 'BOTH'] as const).map(d => <option key={d} value={d}>{t[d]}</option>)}
        </select></label>
        <label>{t.seatsLabel}<input type="number" min={1} max={8} required value={current.seats} onChange={e => setDraft({ ...current, seats: e.target.value })} /></label>
        <label>{t.point}<input required maxLength={200} value={current.point} onChange={e => setDraft({ ...current, point: e.target.value })} /></label>
        <label>{t.date}<input type="date" required value={current.date} onChange={e => setDraft({ ...current, date: e.target.value })} /></label>
        <label>{t.time}<input type="time" required value={current.time} onChange={e => setDraft({ ...current, time: e.target.value })} /></label>
        <p className="muted small">{t.phones}</p>
        <button type="submit" disabled={busy}>{own ? t.save : t.offer}</button>
      </form></Reveal>}
      {list.items.length > 0 && <p className="muted small">{t.phones}</p>}
      {notice && <p role="alert">{t[notice]}</p>}
    </section>
  )

  function fromRide(ride: Ride): Draft {
    const at = localParts({ startsAt: ride.departsAt, endsAt: ride.departsAt })
    return { direction: ride.direction, seats: String(ride.seats), point: ride.meetingPoint, date: at.date, time: at.start }
  }
}
