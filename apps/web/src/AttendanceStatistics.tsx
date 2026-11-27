import { useEffect, useState, type FormEvent } from 'react'
import type { Language } from './language'
import { readAttendanceStatistics, type AttendanceStatistics as Statistics } from './events'

const words = {
  de: { title: 'Anwesenheitsstatistik', from: 'Von', to: 'Bis', apply: 'Anzeigen', held: 'Durchgeführte Termine', yes: 'Ja', no: 'Nein', maybe: 'Vielleicht', noAnswer: 'Keine Antwort', total: 'Total', empty: 'Keine Mitglieder im Team.', note: 'Antworten werden 12 Monate nach dem Termin gelöscht. Wer später ins Team kam, hat frühere Termine als «keine Antwort».', invalid: 'Bitte einen Zeitraum von höchstens einem Jahr wählen.', denied: 'Keine Berechtigung.', error: 'Statistik nicht verfügbar.' },
  en: { title: 'Attendance statistics', from: 'From', to: 'To', apply: 'Show', held: 'Events held', yes: 'Yes', no: 'No', maybe: 'Maybe', noAnswer: 'No answer', total: 'Total', empty: 'No members in this team.', note: 'Answers are deleted 12 months after an event. Members who joined later show earlier events as “no answer”.', invalid: 'Please choose a range of at most one year.', denied: 'No permission.', error: 'Statistics unavailable.' },
  fr: { title: 'Statistiques de présence', from: 'Du', to: 'Au', apply: 'Afficher', held: 'Événements tenus', yes: 'Oui', no: 'Non', maybe: 'Peut-être', noAnswer: 'Pas de réponse', total: 'Total', empty: 'Aucun membre dans cette équipe.', note: 'Les réponses sont supprimées 12 mois après l’événement. Les membres arrivés plus tard ont « pas de réponse » pour les événements antérieurs.', invalid: 'Choisissez une période d’un an au plus.', denied: 'Pas d’autorisation.', error: 'Statistiques indisponibles.' },
  it: { title: 'Statistiche di presenza', from: 'Dal', to: 'Al', apply: 'Mostra', held: 'Eventi svolti', yes: 'Sì', no: 'No', maybe: 'Forse', noAnswer: 'Nessuna risposta', total: 'Totale', empty: 'Nessun membro in questa squadra.', note: 'Le risposte vengono eliminate 12 mesi dopo l’evento. Chi è entrato più tardi ha «nessuna risposta» per gli eventi precedenti.', invalid: 'Scegli un periodo di al massimo un anno.', denied: 'Nessun permesso.', error: 'Statistiche non disponibili.' },
} satisfies Record<Language, Record<string, string>>

/** Attendance statistics of one team (OpenAPI 1.18.0) for its viewers and managers, by public display name. */
export function AttendanceStatistics({ teamId, teamName, language, onRange }: {
  teamId: string; teamName: string; language: Language
  /** The range shown, so the J+S file below uses the same one instead of a second date form. */
  onRange?: (range: { from: string; to: string }) => void
}) {
  const t = words[language]
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [result, setResult] = useState<Statistics | 'denied' | 'invalid' | 'error' | null>(null)

  async function load(range?: { from: string; to: string }) {
    setResult(null)
    try {
      const next = await readAttendanceStatistics(teamId, range?.from || undefined, range?.to || undefined)
      setResult(next)
      if (typeof next === 'object') { setFrom(next.from); setTo(next.to); onRange?.({ from: next.from, to: next.to }) }
    } catch { setResult('error') }
  }

  // The last 12 months by default (the server's choice); another range comes from the form.
  useEffect(() => {
    let active = true
    readAttendanceStatistics(teamId).then(next => {
      if (!active) return
      setResult(next)
      if (typeof next === 'object') { setFrom(next.from); setTo(next.to); onRange?.({ from: next.from, to: next.to }) }
    }).catch(() => { if (active) setResult('error') })
    return () => { active = false }
  }, [teamId])

  const counts = (row: { yes: number; no: number; maybe: number; noAnswer: number }) =>
    `${t.yes} ${row.yes} · ${t.no} ${row.no} · ${t.maybe} ${row.maybe} · ${t.noAnswer} ${row.noAnswer}`

  function apply(event: FormEvent) {
    event.preventDefault()
    void load({ from, to })
  }

  return (
    <section aria-label={`${t.title} ${teamName}`} className="stack">
      <form className="form-grid" onSubmit={apply}>
        <label>{t.from}<input type="date" value={from} required onChange={event => setFrom(event.target.value)} /></label>
        <label>{t.to}<input type="date" value={to} required onChange={event => setTo(event.target.value)} /></label>
        <button type="submit">{t.apply}</button>
      </form>
      {result === null && <p role="status">…</p>}
      {(result === 'denied' || result === 'invalid' || result === 'error') && <p role="alert">{t[result]}</p>}
      {typeof result === 'object' && result !== null && <>
        {/* Totals first, then one compact row per member: the name, a bar for the share of yes, the four counts. */}
        <p><b>{t.held}: {result.held}</b><br /><span className="muted">{t.total}: {counts(result.totals)}</span></p>
        {result.items.length === 0 ? <p>{t.empty}</p> : <ul className="rows card stat-rows">{result.items.map((item, index) =>
          <li key={index} className="pad">
            <span className="stat-name"><b>{item.displayName}</b><span className="muted small">{counts(item)}</span></span>
            <span className="bar" aria-hidden="true"><span style={{ width: `${result.held ? Math.round(100 * item.yes / result.held) : 0}%` }} /></span>
          </li>)}</ul>}
        <p className="muted small">{t.note}</p>
      </>}
    </section>
  )
}
