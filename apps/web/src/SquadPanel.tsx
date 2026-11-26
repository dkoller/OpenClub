import { useEffect, useState } from 'react'
import { formatDayTime } from './dates'
import type { Language } from './language'
import type { MyEvent, TeamEvent } from './events'
import { meetInstant, readSquad, saveSquad, toTime, type Squad } from './squads'
import { Fold } from './Reveal'

const words = {
  de: { title: 'Aufgebot', hint: 'Wer neu aufgeboten wird, erhält eine E-Mail (bei Kindern die Eltern). Die Liste sehen nur Sie; Spieler sehen nur, ob sie dabei sind.', meetAt: 'Besammlung (Zeit)', meetingPoint: 'Besammlungsort', said: 'Antwort', YES: 'Ja', NO: 'Nein', MAYBE: 'Vielleicht', none: 'keine', yes: 'Alle mit Ja auswählen', save: 'Aufgebot speichern', saved: 'Gespeichert.', failed: 'Nicht gespeichert. Bitte Angaben prüfen.', closed: 'Das Spiel hat begonnen oder ist abgesagt.', nobody: 'Niemand im Team.', count: '{n} aufgeboten', called: 'Aufgeboten', notCalled: 'Nicht aufgeboten', meet: 'Besammlung {when}', forName: '{name}: ' },
  en: { title: 'Squad', hint: 'Newly called members get an email (parents for children). Only you see the list; players only see whether they are in.', meetAt: 'Meet at (time)', meetingPoint: 'Meeting point', said: 'Answer', YES: 'Yes', NO: 'No', MAYBE: 'Maybe', none: 'none', yes: 'Select everyone who said yes', save: 'Save squad', saved: 'Saved.', failed: 'Not saved. Please check the fields.', closed: 'The match has started or is cancelled.', nobody: 'Nobody in the team.', count: '{n} called up', called: 'Called up', notCalled: 'Not called up', meet: 'Meet {when}', forName: '{name}: ' },
  fr: { title: 'Convocation', hint: 'Les nouveaux convoqués reçoivent un e-mail (les parents pour les enfants). Vous seul voyez la liste ; les joueurs voient seulement s’ils sont retenus.', meetAt: 'Rendez-vous (heure)', meetingPoint: 'Lieu de rendez-vous', said: 'Réponse', YES: 'Oui', NO: 'Non', MAYBE: 'Peut-être', none: 'aucune', yes: 'Sélectionner tous ceux qui ont dit oui', save: 'Enregistrer la convocation', saved: 'Enregistré.', failed: 'Non enregistré. Veuillez vérifier les champs.', closed: 'Le match a commencé ou est annulé.', nobody: 'Personne dans l’équipe.', count: '{n} convoqués', called: 'Convoqué', notCalled: 'Pas convoqué', meet: 'Rendez-vous {when}', forName: '{name} : ' },
  it: { title: 'Convocazione', hint: 'I nuovi convocati ricevono un’email (i genitori per i figli). Solo voi vedete l’elenco; i giocatori vedono solo se sono convocati.', meetAt: 'Ritrovo (ora)', meetingPoint: 'Luogo di ritrovo', said: 'Risposta', YES: 'Sì', NO: 'No', MAYBE: 'Forse', none: 'nessuna', yes: 'Seleziona tutti quelli che hanno detto sì', save: 'Salva convocazione', saved: 'Salvato.', failed: 'Non salvato. Controllate i campi.', closed: 'La partita è iniziata o è annullata.', nobody: 'Nessuno nella squadra.', count: '{n} convocati', called: 'Convocato', notCalled: 'Non convocato', meet: 'Ritrovo {when}', forName: '{name}: ' },
} satisfies Record<Language, Record<string, string>>

const fill = (text: string, values: Record<string, string | number>) =>
  text.replace(/\{(\w+)\}/g, (_, key: string) => String(values[key] ?? ''))


/** Coach event page, matches only: pick the squad, the meeting time and place. */
export function SquadPanel({ event, language, csrfToken }: { event: TeamEvent; language: Language; csrfToken: string }) {
  const t = words[language]
  const [squad, setSquad] = useState<Squad | 'denied' | 'error' | null>(null)
  const [called, setCalled] = useState<Set<string>>(new Set())
  const [meetAt, setMeetAt] = useState('')
  const [point, setPoint] = useState('')
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null)
  useEffect(() => {
    if (event.kind !== 'MATCH') return
    let active = true
    readSquad(event.id).then(next => {
      if (!active) return
      setSquad(next)
      if (typeof next === 'object') {
        setCalled(new Set(next.items.filter(item => item.called).map(item => item.memberId)))
        setMeetAt(next.meetAt ? toTime(next.meetAt) : ''); setPoint(next.meetingPoint ?? '')
      }
    }).catch(() => { if (active) setSquad('error') })
    return () => { active = false }
  }, [event.id, event.kind])
  if (event.kind !== 'MATCH' || squad === null || squad === 'denied') return null
  if (squad === 'error') return <p role="alert">{t.failed}</p>
  async function save() {
    setBusy(true); setNotice(null)
    try {
      const outcome = await saveSquad(event.id, [...called], meetInstant(meetAt, event.startsAt), point.trim() || null, csrfToken)
      setNotice(outcome === 'ok' ? { ok: true, text: t.saved } : outcome === 'closed' ? { ok: false, text: t.closed } : { ok: false, text: t.failed })
    } catch { setNotice({ ok: false, text: t.failed }) } finally { setBusy(false) }
  }
  const open = squad.open
  return <Fold title={<>{t.title} <span className="muted">({fill(t.count, { n: called.size })})</span></>}>
    {!open ? <p className="muted">{t.closed}</p> : <p className="muted small">{t.hint}</p>}
    {squad.items.length === 0 ? <p>{t.nobody}</p> : <fieldset disabled={!open}>
      <legend>{t.title}</legend>
      {squad.items.map(person => <label key={person.memberId} className="check">
        <input type="checkbox" checked={called.has(person.memberId)} onChange={() => {
          setNotice(null)
          setCalled(current => { const next = new Set(current); if (next.has(person.memberId)) next.delete(person.memberId); else next.add(person.memberId); return next })
        }} />
        {person.displayName} <span className="muted small">({t.said}: {person.response ? t[person.response] : t.none})</span>
      </label>)}
    </fieldset>}
    {open && <>
      <label>{t.meetAt}<input type="time" value={meetAt} onChange={e => setMeetAt(e.target.value)} /></label>
      <label>{t.meetingPoint}<input value={point} maxLength={200} onChange={e => setPoint(e.target.value)} /></label>
      <div className="actions">
        <button type="button" className="btn" disabled={busy} onClick={() => {
          setNotice(null)
          setCalled(new Set(squad.items.filter(item => item.response === 'YES' || called.has(item.memberId)).map(item => item.memberId)))
        }}>{t.yes}</button>
        <button type="button" className="btn primary" disabled={busy} onClick={() => { void save() }}>{t.save}</button>
      </div>
    </>}
    {notice && <p role={notice.ok ? 'status' : 'alert'}>{notice.text}</p>}
  </Fold>
}

/** Member event page: each person's own squad status, once the coach saved a squad. */
export function OwnSquadStatus({ event, self, language }: { event: MyEvent; self: string | null; language: Language }) {
  const t = words[language]
  const people = event.for.filter(person => person.squad !== null)
  if (people.length === 0) return null
  return <section aria-label={t.title} className="card pad stack">
    <h2>{t.title}</h2>
    {people.map(person => {
      const squad = person.squad!
      const when = squad.meetAt ? formatDayTime(squad.meetAt, language) : null
      return <p key={person.id} className={squad.called ? 'attention-ok' : 'muted'}>
        {person.id !== self && fill(t.forName, { name: person.firstName })}<b>{squad.called ? t.called : t.notCalled}</b>
        {squad.called && when && <> · {fill(t.meet, { when })}</>}
        {squad.called && squad.meetingPoint && <> · {squad.meetingPoint}</>}
      </p>
    })}
  </section>
}
