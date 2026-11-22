import { useEffect, useState } from 'react'
import type { Language } from './language'
import { readTeams, type Team } from './teams'
import { formatWhen, readAttendance, readTeamEvents, type AttendanceList, type TeamEvent } from './events'
import { byAnswer, notAnswered, upcoming, type UpcomingEvent } from './coach'
import { DateBlock, eventWords, fill, shortWhen } from './EventBits'
import { routeHash } from './routes'
import { ShiftsPanel } from './ShiftsPanel'
import { RidesPanel } from './RidesPanel'

const words = {
  de: { next: 'Nächste Termine', none: 'In den nächsten 14 Tagen stehen keine Termine an.', summary: '{yes} Ja · {no} Nein', openTag: '{open} offen', allAnswered: 'alle haben geantwortet', more: 'Nur die ersten {n} Teams sind berücksichtigt; alle Termine stehen unten beim Team.', back: 'Zurück zum Team', who: 'Wer kommt?', open: 'Noch offen', YES: 'Ja', NO: 'Nein', MAYBE: 'Vielleicht', nobody: 'Niemand.', gone: 'Diesen Termin gibt es nicht mehr, oder du siehst dieses Team nicht.', noAnswers: 'Die Antworten sind nicht sichtbar.', absence: 'Abwesenheiten zählen als Nein. Gründe werden nie erfasst.' },
  en: { next: 'Next events', none: 'No events in the next 14 days.', summary: '{yes} yes · {no} no', openTag: '{open} open', allAnswered: 'everyone answered', more: 'Only the first {n} teams are included; every event is listed below under its team.', back: 'Back to team', who: 'Who is coming?', open: 'No answer yet', YES: 'Yes', NO: 'No', MAYBE: 'Maybe', nobody: 'Nobody.', gone: 'This event no longer exists, or you cannot see this team.', noAnswers: 'The answers are not visible.', absence: 'Absences count as No. Reasons are never recorded.' },
  fr: { next: 'Prochains événements', none: 'Aucun événement dans les 14 prochains jours.', summary: '{yes} oui · {no} non', openTag: '{open} sans réponse', allAnswered: 'tout le monde a répondu', more: 'Seules les {n} premières équipes sont prises en compte ; tous les événements figurent plus bas sous leur équipe.', back: 'Retour à l’équipe', who: 'Qui vient ?', open: 'Pas encore répondu', YES: 'Oui', NO: 'Non', MAYBE: 'Peut-être', nobody: 'Personne.', gone: 'Cet événement n’existe plus ou tu ne vois pas cette équipe.', noAnswers: 'Les réponses ne sont pas visibles.', absence: 'Les absences comptent comme non. Les motifs ne sont jamais enregistrés.' },
  it: { next: 'Prossimi eventi', none: 'Nessun evento nei prossimi 14 giorni.', summary: '{yes} sì · {no} no', openTag: '{open} senza risposta', allAnswered: 'hanno risposto tutti', more: 'Sono considerate solo le prime {n} squadre; tutti gli eventi sono elencati sotto con la loro squadra.', back: 'Torna alla squadra', who: 'Chi viene?', open: 'Non ancora risposto', YES: 'Sì', NO: 'No', MAYBE: 'Forse', nobody: 'Nessuno.', gone: 'Questo evento non esiste più o non vedi questa squadra.', noAnswers: 'Le risposte non sono visibili.', absence: 'Le assenze contano come no. I motivi non vengono mai registrati.' },
} satisfies Record<Language, Record<string, string>>

const TEAM_LIMIT = 8

/** Team page, top: the next two weeks of the teams this person sees, with who has not answered yet. */
export function CoachOverview({ language }: { language: Language }) {
  const t = { ...eventWords[language], ...words[language] }
  const [items, setItems] = useState<UpcomingEvent[] | null>(null)
  const [capped, setCapped] = useState(false)
  const [state, setState] = useState<'loading' | 'ready' | 'denied' | 'error'>('loading')
  useEffect(() => {
    let active = true
    void (async () => {
      try {
        const teams = await readTeams()
        if (teams === 'denied') { if (active) setState('denied'); return }
        const shown = teams.slice(0, TEAM_LIMIT)
        const schedules = await Promise.all(shown.map(async team => {
          const result = await readTeamEvents(team.id)
          return { team, events: result === 'denied' ? [] : result.items }
        }))
        if (!active) return
        setItems(upcoming(schedules)); setCapped(teams.length > TEAM_LIMIT); setState('ready')
      } catch { if (active) setState('error') }
    })()
    return () => { active = false }
  }, [])
  if (state === 'denied') return null
  return <section aria-labelledby="coach-next" className="stack">
    <h2 id="coach-next">{t.next}</h2>
    {state === 'loading' && <p role="status">{t.loading}</p>}
    {state === 'error' && <p role="alert">{t.error}</p>}
    {state === 'ready' && items?.length === 0 && <p className="muted">{t.none}</p>}
    {items && items.length > 0 && <ul className="rows card">{items.map(({ team, event }) => {
      const open = notAnswered(event.counts, team.memberCount)
      return <li key={event.id}><a className="row" href={routeHash({ page: 'team-event', teamId: team.id, eventId: event.id })}>
        <DateBlock instant={event.startsAt} language={language} />
        <span className="event-text"><b>{event.title}</b>
          <span className="muted">{team.name} · {shortWhen(event.startsAt, language)}</span>
          {event.cancelled ? <span><span className="open">{t.cancelled}</span></span>
            : <span>{fill(t.summary, { yes: event.counts.yes, no: event.counts.no })}{event.counts.maybe > 0 && ` · ${event.counts.maybe} ${t.MAYBE}`}
              {' · '}{open > 0 ? <span className="open">{fill(t.openTag, { open })}</span> : t.allAnswered}</span>}
        </span>
      </a></li>
    })}</ul>}
    {capped && <p className="muted small">{fill(t.more, { n: TEAM_LIMIT })}</p>}
  </section>
}

/** One event for its coaches: totals, names by answer (open first), then helper shifts and rides. */
export function CoachEventPage({ teamId, eventId, language, csrfToken, self }: {
  teamId: string; eventId: string; language: Language; csrfToken: string; self: string | null
}) {
  const t = { ...eventWords[language], ...words[language] }
  const [event, setEvent] = useState<TeamEvent | null>(null)
  const [team, setTeam] = useState<Team | null>(null)
  const [answers, setAnswers] = useState<AttendanceList | 'denied' | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'gone' | 'error'>('loading')
  useEffect(() => {
    let active = true
    void (async () => {
      try {
        const [teams, schedule] = await Promise.all([readTeams(), readTeamEvents(teamId)])
        const found = schedule === 'denied' ? undefined : schedule.items.find(item => item.id === eventId)
        if (!found) { if (active) setState('gone'); return }
        const list = await readAttendance(eventId)
        if (!active) return
        setEvent(found); setTeam(teams === 'denied' ? null : teams.find(item => item.id === teamId) ?? null)
        setAnswers(list); setState('ready')
      } catch { if (active) setState('error') }
    })()
    return () => { active = false }
  }, [teamId, eventId])
  const groups = answers && answers !== 'denied' ? byAnswer(answers) : null
  return <>
    <p><a href={routeHash({ page: 'team' })}>← {t.back}</a></p>
    {state === 'loading' && <p role="status">{t.loading}</p>}
    {state === 'error' && <p role="alert">{t.error}</p>}
    {state === 'gone' && <p>{t.gone}</p>}
    {event && <>
      <h1>{event.title}</h1>
      <p><b>{formatWhen(event, language)}</b>{event.cancelled && <> <span className="open">{t.cancelled}</span></>}</p>
      <p className="muted">{t[event.kind]} · {team?.name ?? event.teamName}{event.location ? ' · ' + event.location : ''}</p>
      {event.notes && <p className="notes">{event.notes}</p>}
      <section aria-labelledby="who" className="stack">
        <h2 id="who">{t.who}</h2>
        {!groups ? <p className="muted">{t.noAnswers}</p> : <>
          <div className="tally">
            {(['YES', 'NO', 'open'] as const).map(key => <p key={key} className={key === 'open' && groups.open.length > 0 ? 'card pad attention' : 'card pad'}>
              <b>{groups[key].length}</b> <span>{key === 'open' ? t.open : t[key]}</span></p>)}
          </div>
          {(['open', 'NO', 'MAYBE', 'YES'] as const).map(key => (key !== 'MAYBE' || groups.MAYBE.length > 0) &&
            <section key={key} aria-label={key === 'open' ? t.open : t[key]} className="card pad">
              <h3>{key === 'open' ? t.open : t[key]} ({groups[key].length})</h3>
              <p>{groups[key].length > 0 ? groups[key].join(', ') : t.nobody}</p>
            </section>)}
          <p className="muted small">{t.absence}</p>
        </>}
      </section>
      <ShiftsPanel event={event} language={language} csrfToken={csrfToken} self={self} />
      <RidesPanel event={event} language={language} csrfToken={csrfToken} self={self} />
    </>}
  </>
}
