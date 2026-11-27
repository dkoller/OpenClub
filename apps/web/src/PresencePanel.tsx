import { useEffect, useState } from 'react'
import type { Language } from './language'
import { checkJs, jsFileHref, readPresence, savePresence, type JsCheck, type Presence } from './presence'

const words = {
  de: { title: 'Wer war da?', hint: 'Nach dem Termin abhaken, wer tatsächlich gekommen ist. Gilt für die J+S-Anwesenheitskontrolle.', leaders: 'Leitung', participants: 'Teilnehmende', said: 'Antwort', YES: 'Ja', NO: 'Nein', MAYBE: 'Vielleicht', none: 'keine', all: 'Alle mit Ja abhaken', save: 'Anwesenheit speichern', saved: 'Gespeichert.', failed: 'Nicht gespeichert. Bitte nochmals versuchen.', early: 'Abhaken geht erst, wenn der Termin begonnen hat.', cancelled: 'Abgesagt – keine Anwesenheit.', nobody: 'Niemand im Team.', jsTitle: 'J+S-Anwesenheit', jsHint: 'Datei für den Import der Anwesenheitskontrolle in die NDS. Trainings und Spiele, die abgehakt wurden. Gleicher Zeitraum wie oben.', from: 'Von', to: 'Bis', check: 'Prüfen', rows: '{n} Zeilen', missingNumber: 'Ohne J+S-Nummer (fehlen in der Datei): {names}', missingPlace: 'Trainings ohne Ort (die NDS braucht einen Ort): {list}', download: 'Datei herunterladen', invalid: 'Bitte einen Zeitraum von höchstens einem Jahr wählen.', error: 'Gerade nicht verfügbar.' },
  en: { title: 'Who came?', hint: 'After the event, tick who actually came. Used for the J+S attendance record.', leaders: 'Leaders', participants: 'Participants', said: 'Answer', YES: 'Yes', NO: 'No', MAYBE: 'Maybe', none: 'none', all: 'Tick everyone who said yes', save: 'Save attendance', saved: 'Saved.', failed: 'Not saved. Please try again.', early: 'You can tick once the event has started.', cancelled: 'Cancelled – no attendance.', nobody: 'Nobody in the team.', jsTitle: 'J+S attendance', jsHint: 'File for importing the attendance record into the NDS. Trainings and matches with ticks. Same period as above.', from: 'From', to: 'To', check: 'Check', rows: '{n} rows', missingNumber: 'Without a J+S number (left out of the file): {names}', missingPlace: 'Trainings without a place (the NDS needs one): {list}', download: 'Download file', invalid: 'Please choose a range of at most one year.', error: 'Unavailable right now.' },
  fr: { title: 'Qui était là ?', hint: 'Après l’événement, cochez qui est vraiment venu. Sert au contrôle des présences J+S.', leaders: 'Moniteurs', participants: 'Participants', said: 'Réponse', YES: 'Oui', NO: 'Non', MAYBE: 'Peut-être', none: 'aucune', all: 'Cocher tous ceux qui ont dit oui', save: 'Enregistrer les présences', saved: 'Enregistré.', failed: 'Non enregistré. Veuillez réessayer.', early: 'Vous pourrez cocher dès que l’événement a commencé.', cancelled: 'Annulé – pas de présences.', nobody: 'Personne dans l’équipe.', jsTitle: 'Présences J+S', jsHint: 'Fichier pour importer le contrôle des présences dans la BDNS. Entraînements et matchs cochés. Même période que ci-dessus.', from: 'Du', to: 'Au', check: 'Vérifier', rows: '{n} lignes', missingNumber: 'Sans numéro J+S (absents du fichier) : {names}', missingPlace: 'Entraînements sans lieu (la BDNS en demande un) : {list}', download: 'Télécharger le fichier', invalid: 'Choisissez une période d’un an au plus.', error: 'Indisponible pour le moment.' },
  it: { title: 'Chi c’era?', hint: 'Dopo l’evento, spunta chi è venuto davvero. Serve per il controllo delle presenze G+S.', leaders: 'Monitori', participants: 'Partecipanti', said: 'Risposta', YES: 'Sì', NO: 'No', MAYBE: 'Forse', none: 'nessuna', all: 'Spunta tutti quelli che hanno detto sì', save: 'Salva presenze', saved: 'Salvato.', failed: 'Non salvato. Riprovate.', early: 'Potete spuntare quando l’evento è iniziato.', cancelled: 'Annullato – nessuna presenza.', nobody: 'Nessuno nella squadra.', jsTitle: 'Presenze G+S', jsHint: 'File per importare il controllo delle presenze nella BDNS. Allenamenti e partite spuntati. Stesso periodo di sopra.', from: 'Dal', to: 'Al', check: 'Verifica', rows: '{n} righe', missingNumber: 'Senza numero G+S (esclusi dal file): {names}', missingPlace: 'Allenamenti senza luogo (la BDNS lo richiede): {list}', download: 'Scarica file', invalid: 'Scegli un periodo di al massimo un anno.', error: 'Non disponibile al momento.' },
} satisfies Record<Language, Record<string, string>>

const fill = (text: string, values: Record<string, string | number>) =>
  text.replace(/\{(\w+)\}/g, (_, key: string) => String(values[key] ?? ''))

/** Coach event page: tick who came, once the event has started (only for the team's managers). */
export function PresencePanel({ eventId, cancelled, language, csrfToken }: {
  eventId: string; cancelled: boolean; language: Language; csrfToken: string
}) {
  const t = words[language]
  const [presence, setPresence] = useState<Presence | 'denied' | 'error' | null>(null)
  const [ticked, setTicked] = useState<Set<string>>(new Set())
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null)
  useEffect(() => {
    let active = true
    readPresence(eventId).then(next => {
      if (!active) return
      setPresence(next)
      if (typeof next === 'object') setTicked(new Set(next.items.filter(item => item.present).map(item => item.memberId)))
    }).catch(() => { if (active) setPresence('error') })
    return () => { active = false }
  }, [eventId])
  // Before the start (and for a cancelled event) there is nothing to tick, so the page does not show the part at all.
  if (presence === null || presence === 'denied' || cancelled || (typeof presence === 'object' && !presence.open)) return null
  if (presence === 'error') return <p role="alert">{t.error}</p>
  function toggle(id: string) {
    setNotice(null)
    setTicked(current => { const next = new Set(current); if (next.has(id)) next.delete(id); else next.add(id); return next })
  }
  async function save() {
    setBusy(true); setNotice(null)
    try {
      const outcome = await savePresence(eventId, [...ticked], csrfToken)
      setNotice(outcome === 'ok' ? { ok: true, text: t.saved } : outcome === 'not_started' ? { ok: false, text: t.early } : { ok: false, text: t.failed })
    } catch { setNotice({ ok: false, text: t.failed }) } finally { setBusy(false) }
  }
  const groups = [['LEADER', t.leaders], ['PARTICIPANT', t.participants]] as const
  return <section aria-labelledby="presence" className="card pad stack panel">
    <h2 id="presence">{t.title} <span className="muted small">({ticked.size})</span></h2>
    {<>
      <p className="muted small">{t.hint}</p>
      {presence.items.length === 0 && <p>{t.nobody}</p>}
      {groups.map(([role, label]) => {
        const people = presence.items.filter(item => item.role === role)
        return people.length > 0 && <fieldset key={role}>
          <legend>{label}</legend>
          {people.map(person => <label key={person.memberId} className="check">
            <input type="checkbox" checked={ticked.has(person.memberId)} onChange={() => toggle(person.memberId)} />
            {person.displayName} <span className="muted small">({t.said}: {person.response ? t[person.response] : t.none})</span>
          </label>)}
        </fieldset>
      })}
      <div className="actions">
        <button type="button" className="btn" disabled={busy} onClick={() => {
          setNotice(null)
          setTicked(new Set(presence.items.filter(item => item.response === 'YES' || ticked.has(item.memberId)).map(item => item.memberId)))
        }}>{t.all}</button>
        <button type="button" className="btn primary" disabled={busy} onClick={() => { void save() }}>{t.save}</button>
      </div>
      {notice && <p role={notice.ok ? 'status' : 'alert'}>{notice.text}</p>}
    </>}
  </section>
}

/** Team statistics page: check and download the J+S file (only for the team's managers). */
export function JsExport({ teamId, language, range }: {
  teamId: string; language: Language
  /** The period chosen for the statistics above; the file covers the same one. */
  range: { from: string; to: string } | null
}) {
  const t = words[language]
  const [result, setResult] = useState<JsCheck | 'denied' | 'invalid' | 'error' | null>(null)
  useEffect(() => {
    if (!range) return
    let active = true
    checkJs(teamId, range.from, range.to).then(next => { if (active) setResult(next) }).catch(() => { if (active) setResult('error') })
    return () => { active = false }
  }, [teamId, range?.from, range?.to])
  if (result === null || result === 'denied') return null
  return <section aria-labelledby="js-export" className="stack">
    <h2 id="js-export">{t.jsTitle}</h2>
    <p className="muted small">{t.jsHint}</p>
    {(result === 'invalid' || result === 'error') && <p role="alert">{t[result]}</p>}
    {typeof result === 'object' && <>
      <p>{fill(t.rows, { n: result.rows })}</p>
      {result.withoutNumber.length > 0 && <p className="card pad attention">{fill(t.missingNumber, { names: result.withoutNumber.join(', ') })}</p>}
      {result.trainingsWithoutPlace.length > 0 && <p className="card pad attention">{fill(t.missingPlace, { list: result.trainingsWithoutPlace.join(', ') })}</p>}
      {result.rows > 0 && <a className="btn primary" href={jsFileHref(teamId, result.from, result.to)} download>{t.download}</a>}
    </>}
  </section>
}
