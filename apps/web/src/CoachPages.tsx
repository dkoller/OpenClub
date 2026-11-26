import { useEffect, useState } from 'react'
import { BackLink } from './BackLink'
import type { Language } from './language'
import { readTeams, type Team } from './teams'
import { answerable, formatWhen, readAttendance, readTeamEvents, remindMissing, type AttendanceList, type TeamEvent } from './events'
import { byAnswer, notAnswered, upcoming, type UpcomingEvent } from './coach'
import { DateBlock, eventWords, fill } from './EventBits'
import { formatTime } from './dates'
import { ManageEvent } from './TeamSchedule'
import { routeHash } from './routes'
import { ShiftsPanel } from './ShiftsPanel'
import { RidesPanel } from './RidesPanel'
import { PresencePanel } from './PresencePanel'
import { SquadPanel } from './SquadPanel'
import { MessagePanel } from './MessagePanel'

const words = {
  de: { remindOne: 'Die Person ohne Antwort erinnern', next: 'Nächste Termine', none: 'In den nächsten 14 Tagen stehen keine Termine an.', summary: '{yes} Ja · {no} Nein', openTag: '{open} offen', allAnswered: 'alle haben geantwortet', more: 'Nur die ersten {n} Teams sind berücksichtigt; alle Termine stehen im Programm des jeweiligen Teams.', back: 'Zurück zum Team', who: 'Wer kommt?', open: 'Noch offen', YES: 'Ja', NO: 'Nein', MAYBE: 'Vielleicht', nobody: 'Niemand.', gone: 'Diesen Termin gibt es nicht mehr, oder du siehst dieses Team nicht.', noAnswers: 'Die Antworten sind nicht sichtbar.', absence: 'Abwesenheiten zählen als Nein. Gründe werden nie erfasst.', remind: 'Alle {n} ohne Antwort erinnern', reminded: 'Erinnerung an {n} verschickt (per E-Mail, an Eltern für ihre Kinder).', recent: 'Für diesen Termin wurde in den letzten 12 Stunden schon erinnert.', emailOff: 'Dieser Verein versendet keine E-Mails.', remindFailed: 'Erinnerung nicht verschickt. Bitte später nochmals versuchen.' },
  en: { remindOne: 'Remind the one without an answer', next: 'Next events', none: 'No events in the next 14 days.', summary: '{yes} yes · {no} no', openTag: '{open} open', allAnswered: 'everyone answered', more: 'Only the first {n} teams are included; every event is in its team’s schedule.', back: 'Back to team', who: 'Who is coming?', open: 'No answer yet', YES: 'Yes', NO: 'No', MAYBE: 'Maybe', nobody: 'Nobody.', gone: 'This event no longer exists, or you cannot see this team.', noAnswers: 'The answers are not visible.', absence: 'Absences count as No. Reasons are never recorded.', remind: 'Remind all {n} without an answer', reminded: 'Reminder sent to {n} (by email, to parents for their children).', recent: 'A reminder for this event was already sent in the last 12 hours.', emailOff: 'This club does not send emails.', remindFailed: 'Reminder not sent. Please try again later.' },
  fr: { remindOne: 'Relancer la personne sans réponse', next: 'Prochains événements', none: 'Aucun événement dans les 14 prochains jours.', summary: '{yes} oui · {no} non', openTag: '{open} sans réponse', allAnswered: 'tout le monde a répondu', more: 'Seules les {n} premières équipes sont prises en compte ; tous les événements figurent dans le programme de chaque équipe.', back: 'Retour à l’équipe', who: 'Qui vient ?', open: 'Pas encore répondu', YES: 'Oui', NO: 'Non', MAYBE: 'Peut-être', nobody: 'Personne.', gone: 'Cet événement n’existe plus ou vous ne voyez pas cette équipe.', noAnswers: 'Les réponses ne sont pas visibles.', absence: 'Les absences comptent comme non. Les motifs ne sont jamais enregistrés.', remind: 'Relancer les {n} sans réponse', reminded: 'Rappel envoyé à {n} (par e-mail, aux parents pour leurs enfants).', recent: 'Un rappel a déjà été envoyé pour cet événement ces 12 dernières heures.', emailOff: 'Ce club n’envoie pas d’e-mails.', remindFailed: 'Rappel non envoyé. Veuillez réessayer plus tard.' },
  it: { remindOne: 'Ricorda alla persona senza risposta', next: 'Prossimi eventi', none: 'Nessun evento nei prossimi 14 giorni.', summary: '{yes} sì · {no} no', openTag: '{open} senza risposta', allAnswered: 'hanno risposto tutti', more: 'Sono considerate solo le prime {n} squadre; tutti gli eventi sono nel programma di ogni squadra.', back: 'Torna alla squadra', who: 'Chi viene?', open: 'Non ancora risposto', YES: 'Sì', NO: 'No', MAYBE: 'Forse', nobody: 'Nessuno.', gone: 'Questo evento non esiste più o non vedi questa squadra.', noAnswers: 'Le risposte non sono visibili.', absence: 'Le assenze contano come no. I motivi non vengono mai registrati.', remind: 'Ricorda a tutti i {n} senza risposta', reminded: 'Promemoria inviato a {n} (via email, ai genitori per i figli).', recent: 'Per questo evento è già stato inviato un promemoria nelle ultime 12 ore.', emailOff: 'Questo club non invia email.', remindFailed: 'Promemoria non inviato. Riprovate più tardi.' },
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
          <span className="muted">{team.name} · {formatTime(event.startsAt)}</span>
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
  const [canManage, setCanManage] = useState(false)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null)
  async function remind() {
    setBusy(true); setNotice(null)
    try {
      const outcome = await remindMissing(eventId, csrfToken)
      setNotice(typeof outcome === 'object' ? { ok: true, text: fill(t.reminded, { n: outcome.reminded }) }
        : outcome === 'recent' ? { ok: false, text: t.recent } : outcome === 'email_off' ? { ok: false, text: t.emailOff }
        : { ok: false, text: t.remindFailed })
    } catch { setNotice({ ok: false, text: t.remindFailed }) } finally { setBusy(false) }
  }
  const [state, setState] = useState<'loading' | 'ready' | 'gone' | 'error'>('loading')
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    void (async () => {
      try {
        // Half a year back and ahead, so a coach can open a past training to tick who came.
        const now = Date.now(), day = 86_400_000
        const range = { from: new Date(now - 180 * day), to: new Date(now + 180 * day) }
        const [teams, schedule] = await Promise.all([readTeams(), readTeamEvents(teamId, fetch, range)])
        const found = schedule === 'denied' ? undefined : schedule.items.find(item => item.id === eventId)
        if (!found) { if (active) setState('gone'); return }
        const list = await readAttendance(eventId)
        if (!active) return
        setEvent(found); setTeam(teams === 'denied' ? null : teams.find(item => item.id === teamId) ?? null)
        setCanManage(schedule !== 'denied' && schedule.canManage)
        setAnswers(list); setState('ready')
      } catch { if (active) setState('error') }
    })()
    return () => { active = false }
  }, [teamId, eventId, revision])
  const groups = answers && answers !== 'denied' ? byAnswer(answers) : null
  return <>
    <BackLink href={routeHash({ page: 'team', teamId })} label={team?.name ?? event?.teamName ?? t.back} />
    {state === 'loading' && <p role="status">{t.loading}</p>}
    {state === 'error' && <p role="alert">{t.error}</p>}
    {state === 'gone' && <p>{t.gone}</p>}
    {event && <>
      <h1>{event.title}</h1>
      <p><b>{formatWhen(event, language)}</b>{event.cancelled && <> <span className="open">{t.cancelled}</span></>}</p>
      <p className="muted">{t[event.kind]} · {team?.name ?? event.teamName}{event.location ? ' · ' + event.location : ''}</p>
      {event.notes && <p className="notes">{event.notes}</p>}
      {canManage && <ManageEvent event={event} language={language} csrfToken={csrfToken}
        onChanged={() => setRevision(value => value + 1)}
        onDeleted={() => { window.location.hash = routeHash({ page: 'team', teamId, section: 'schedule' }) }} />}
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
          {canManage && groups.open.length > 0 && event && answerable(event) &&
            <button type="button" className="btn primary" disabled={busy} onClick={() => { void remind() }}>
              {groups.open.length === 1 ? t.remindOne : fill(t.remind, { n: groups.open.length })}</button>}
          {notice && <p role={notice.ok ? 'status' : 'alert'}>{notice.text}</p>}
          <p className="muted small">{t.absence}</p>
        </>}
      </section>
      {canManage && <MessagePanel event={event} language={language} csrfToken={csrfToken} />}
      {canManage && <SquadPanel event={event} language={language} csrfToken={csrfToken} />}
      {canManage && <PresencePanel eventId={event.id} cancelled={event.cancelled} language={language} csrfToken={csrfToken} />}
      <ShiftsPanel event={event} language={language} csrfToken={csrfToken} self={self} />
      <RidesPanel event={event} language={language} csrfToken={csrfToken} self={self} />
    </>}
  </>
}
