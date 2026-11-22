import { useState, type FormEvent } from 'react'
import type { Language } from './language'
import { readAttendanceStatistics, type AttendanceStatistics as Statistics } from './events'

const words = {
  de: { show: 'Anwesenheitsstatistik', hide: 'Statistik schliessen', title: 'Anwesenheitsstatistik', from: 'Von', to: 'Bis', apply: 'Anzeigen', held: 'Durchgeführte Termine', member: 'Mitglied', yes: 'Ja', no: 'Nein', maybe: 'Vielleicht', noAnswer: 'Keine Antwort', total: 'Total', empty: 'Keine Mitglieder im Team.', note: 'Antworten werden 12 Monate nach dem Termin gelöscht. Wer später ins Team kam, hat frühere Termine als «keine Antwort».', invalid: 'Bitte einen Zeitraum von höchstens einem Jahr wählen.', denied: 'Keine Berechtigung.', error: 'Statistik nicht verfügbar.' },
  en: { show: 'Attendance statistics', hide: 'Close statistics', title: 'Attendance statistics', from: 'From', to: 'To', apply: 'Show', held: 'Events held', member: 'Member', yes: 'Yes', no: 'No', maybe: 'Maybe', noAnswer: 'No answer', total: 'Total', empty: 'No members in this team.', note: 'Answers are deleted 12 months after an event. Members who joined later show earlier events as “no answer”.', invalid: 'Please choose a range of at most one year.', denied: 'No permission.', error: 'Statistics unavailable.' },
  fr: { show: 'Statistiques de présence', hide: 'Fermer les statistiques', title: 'Statistiques de présence', from: 'Du', to: 'Au', apply: 'Afficher', held: 'Événements tenus', member: 'Membre', yes: 'Oui', no: 'Non', maybe: 'Peut-être', noAnswer: 'Pas de réponse', total: 'Total', empty: 'Aucun membre dans cette équipe.', note: 'Les réponses sont supprimées 12 mois après l’événement. Les membres arrivés plus tard ont « pas de réponse » pour les événements antérieurs.', invalid: 'Choisissez une période d’un an au plus.', denied: 'Pas d’autorisation.', error: 'Statistiques indisponibles.' },
  it: { show: 'Statistiche di presenza', hide: 'Chiudi statistiche', title: 'Statistiche di presenza', from: 'Dal', to: 'Al', apply: 'Mostra', held: 'Eventi svolti', member: 'Membro', yes: 'Sì', no: 'No', maybe: 'Forse', noAnswer: 'Nessuna risposta', total: 'Totale', empty: 'Nessun membro in questa squadra.', note: 'Le risposte vengono eliminate 12 mesi dopo l’evento. Chi è entrato più tardi ha «nessuna risposta» per gli eventi precedenti.', invalid: 'Scegli un periodo di al massimo un anno.', denied: 'Nessun permesso.', error: 'Statistiche non disponibili.' },
} satisfies Record<Language, Record<string, string>>

/** Attendance statistics of one team (OpenAPI 1.18.0) for its viewers and managers, by public display name. */
export function AttendanceStatistics({ teamId, teamName, language }: { teamId: string; teamName: string; language: Language }) {
  const t = words[language]
  const [open, setOpen] = useState(false)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [result, setResult] = useState<Statistics | 'denied' | 'invalid' | 'error' | null>(null)

  async function load(range?: { from: string; to: string }) {
    setResult(null)
    try {
      const next = await readAttendanceStatistics(teamId, range?.from || undefined, range?.to || undefined)
      setResult(next)
      if (typeof next === 'object') { setFrom(next.from); setTo(next.to) }
    } catch { setResult('error') }
  }

  function apply(event: FormEvent) {
    event.preventDefault()
    void load({ from, to })
  }

  return (
    <div>
      <button type="button" aria-expanded={open} onClick={() => { const next = !open; setOpen(next); if (next) void load() }}>
        {open ? t.hide : t.show}</button>
      {open && <section aria-label={`${t.title} ${teamName}`}>
        <form onSubmit={apply}>
          <label>{t.from}<input type="date" value={from} required onChange={event => setFrom(event.target.value)} /></label>
          <label>{t.to}<input type="date" value={to} required onChange={event => setTo(event.target.value)} /></label>
          <button type="submit">{t.apply}</button>
        </form>
        {result === null && <p role="status">…</p>}
        {(result === 'denied' || result === 'invalid' || result === 'error') && <p role="alert">{t[result]}</p>}
        {typeof result === 'object' && result !== null && <>
          <p>{t.held}: {result.held}</p>
          {result.items.length === 0 ? <p>{t.empty}</p> : <table className="cards">
            <caption>{`${t.title} ${teamName}`}</caption>
            <thead><tr><th scope="col">{t.member}</th><th scope="col">{t.yes}</th><th scope="col">{t.no}</th><th scope="col">{t.maybe}</th><th scope="col">{t.noAnswer}</th></tr></thead>
            <tbody>{result.items.map((item, index) => <tr key={index}>
              <th scope="row">{item.displayName}</th><td data-label={t.yes}>{item.yes}</td><td data-label={t.no}>{item.no}</td><td data-label={t.maybe}>{item.maybe}</td><td data-label={t.noAnswer}>{item.noAnswer}</td>
            </tr>)}</tbody>
            <tfoot><tr><th scope="row">{t.total}</th><td data-label={t.yes}>{result.totals.yes}</td><td data-label={t.no}>{result.totals.no}</td><td data-label={t.maybe}>{result.totals.maybe}</td><td data-label={t.noAnswer}>{result.totals.noAnswer}</td></tr></tfoot>
          </table>}
          <p>{t.note}</p>
        </>}
      </section>}
    </div>
  )
}
