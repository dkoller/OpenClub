import { useEffect, useState, type FormEvent } from 'react'
import { BackLink } from './BackLink'
import type { Language } from './language'
import { routeHash, teamSections, type TeamSection } from './routes'
import { deleteTeam as requestDeleteTeam, readTeams, renameTeam as requestRenameTeam, type Team } from './teams'
import { memberCount } from './TeamDirectory'
import { TeamSchedule } from './TeamSchedule'
import { TeamMembers } from './TeamMembers'
import { AttendanceStatistics } from './AttendanceStatistics'
import { JsExport } from './PresencePanel'

const words = {
  de: { loading: 'Wird geladen…', error: 'Das Team ist gerade nicht verfügbar.', retry: 'Erneut versuchen', gone: 'Dieses Team gibt es nicht mehr, oder du siehst es nicht.', back: 'Alle Teams', parts: 'Teambereiche', schedule: 'Programm', scheduleHint: 'Trainings und Spiele, wer kommt, Helfer und Fahrten', members: 'Mitglieder', statistics: 'Statistik', statisticsHint: 'Wer wie oft dabei war', manage: 'Team verwalten', name: 'Teamname', rename: 'Umbenennen', save: 'Speichern', cancel: 'Abbrechen', renameError: 'Team konnte nicht umbenannt werden.', delete: 'Team löschen', deleteWarning: 'Team und alle Zuordnungen werden gelöscht. Teamnamen zur Bestätigung eingeben.', confirmDelete: 'Endgültig löschen', deleteError: 'Team konnte nicht gelöscht werden.', denied: 'Keine Berechtigung, dieses Team zu ändern.' },
  en: { loading: 'Loading…', error: 'This team is unavailable right now.', retry: 'Try again', gone: 'This team no longer exists, or you cannot see it.', back: 'All teams', parts: 'Team pages', schedule: 'Schedule', scheduleHint: 'Trainings and matches, who is coming, helpers and rides', members: 'Members', statistics: 'Statistics', statisticsHint: 'Who came how often', manage: 'Manage team', name: 'Team name', rename: 'Rename', save: 'Save', cancel: 'Cancel', renameError: 'Could not rename team.', delete: 'Delete team', deleteWarning: 'The team and all assignments will be deleted. Enter its name to confirm.', confirmDelete: 'Delete permanently', deleteError: 'Could not delete team.', denied: 'No permission to change this team.' },
  fr: { loading: 'Chargement…', error: 'Cette équipe est indisponible pour le moment.', retry: 'Réessayer', gone: 'Cette équipe n’existe plus ou vous ne la voyez pas.', back: 'Toutes les équipes', parts: 'Pages de l’équipe', schedule: 'Programme', scheduleHint: 'Entraînements et matchs, qui vient, bénévoles et covoiturage', members: 'Membres', statistics: 'Statistiques', statisticsHint: 'Qui est venu combien de fois', manage: 'Gérer l’équipe', name: 'Nom de l’équipe', rename: 'Renommer', save: 'Enregistrer', cancel: 'Annuler', renameError: 'Impossible de renommer l’équipe.', delete: 'Supprimer l’équipe', deleteWarning: 'L’équipe et toutes ses affectations seront supprimées. Saisissez son nom pour confirmer.', confirmDelete: 'Supprimer définitivement', deleteError: 'Impossible de supprimer l’équipe.', denied: 'Vous n’avez pas l’autorisation de modifier cette équipe.' },
  it: { loading: 'Caricamento…', error: 'Questa squadra non è disponibile al momento.', retry: 'Riprova', gone: 'Questa squadra non esiste più o non la vedi.', back: 'Tutte le squadre', parts: 'Pagine della squadra', schedule: 'Programma', scheduleHint: 'Allenamenti e partite, chi viene, aiutanti e passaggi', members: 'Membri', statistics: 'Statistiche', statisticsHint: 'Chi è venuto quante volte', manage: 'Gestisci squadra', name: 'Nome squadra', rename: 'Rinomina', save: 'Salva', cancel: 'Annulla', renameError: 'Impossibile rinominare la squadra.', delete: 'Elimina squadra', deleteWarning: 'La squadra e tutte le assegnazioni saranno eliminate. Inserisci il nome per confermare.', confirmDelete: 'Elimina definitivamente', deleteError: 'Impossibile eliminare la squadra.', denied: 'Nessuna autorizzazione per modificare questa squadra.' },
} satisfies Record<Language, Record<string, string>>

/**
 * Rename and delete (the typed name confirms), moved here from the team list. The server decides who may: after a
 * refusal both disappear, as they did in the list.
 */
function ManageTeam({ team, language, csrfToken, onRenamed, onDenied }: {
  team: Team; language: Language; csrfToken: string; onRenamed: () => void; onDenied: () => void
}) {
  const t = words[language]
  const [busy, setBusy] = useState(false)
  const [refused, setRefused] = useState(false)
  const [editing, setEditing] = useState<string | null>(null)
  const [renameError, setRenameError] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [confirmation, setConfirmation] = useState('')
  const [deleteError, setDeleteError] = useState(false)

  async function rename(event: FormEvent) {
    event.preventDefault()
    if (editing === null) return
    setBusy(true); setRenameError(false)
    try {
      const outcome = await requestRenameTeam(team.id, editing, csrfToken)
      if (outcome === 'unauthenticated') { onDenied(); return }
      if (outcome === 'forbidden') { setRefused(true); setEditing(null); return }
      if (outcome !== 'ok') throw new Error('Rename rejected')
      setEditing(null)
      onRenamed()
    } catch {
      setRenameError(true)
    } finally {
      setBusy(false)
    }
  }

  async function remove(event: FormEvent) {
    event.preventDefault()
    if (confirmation !== team.name) return
    setBusy(true); setDeleteError(false)
    try {
      const outcome = await requestDeleteTeam(team.id, confirmation, csrfToken)
      if (outcome === 'unauthenticated') { onDenied(); return }
      if (outcome === 'forbidden') { setRefused(true); setDeleting(false); return }
      if (outcome !== 'ok') throw new Error('Deletion rejected')
      window.location.hash = routeHash({ page: 'team' })
    } catch {
      setDeleteError(true)
    } finally {
      setBusy(false)
    }
  }

  if (refused) return <p className="muted">{t.denied}</p>
  return <section aria-labelledby="team-manage" className="card pad stack">
    <h2 id="team-manage">{t.manage}</h2>
    {editing !== null ? <form onSubmit={event => { void rename(event) }}>
      <label>{t.name}<input value={editing} maxLength={200} required onChange={event => setEditing(event.target.value)} /></label>
      <div className="actions">
        <button type="submit" disabled={busy}>{t.save}</button>
        <button type="button" disabled={busy} onClick={() => setEditing(null)}>{t.cancel}</button>
      </div>
    </form> : !deleting && <>
      <div className="actions">
        <button type="button" disabled={busy} onClick={() => { setEditing(team.name); setRenameError(false) }}>{t.rename}</button>
      </div>
      {/* Deleting is rare and final: a quiet red link under the everyday action, not a twin button beside it. */}
      <button type="button" className="danger-quiet" disabled={busy}
        onClick={() => { setDeleting(true); setConfirmation(''); setDeleteError(false) }}>{t.delete}</button>
    </>}
    {deleting && <form onSubmit={event => { void remove(event) }}>
      <p>{t.deleteWarning}</p>
      <label>{t.name}<input value={confirmation} onChange={event => setConfirmation(event.target.value)} required autoComplete="off" /></label>
      <div className="actions">
        <button type="submit" disabled={busy || confirmation !== team.name}>{t.confirmDelete}</button>
        <button type="button" disabled={busy} onClick={() => setDeleting(false)}>{t.cancel}</button>
      </div>
    </form>}
    {renameError && <p role="alert">{t.renameError}</p>}
    {deleteError && <p role="alert">{t.deleteError}</p>}
  </section>
}

/** The team's own page: its name, one large link per part (Members shows the size), and Rename/Delete. */
function TeamHome({ team, language, csrfToken, onChanged, onDenied }: {
  team: Team; language: Language; csrfToken: string; onChanged: () => void; onDenied: () => void
}) {
  const t = words[language]
  const hint: Record<TeamSection, string> = {
    schedule: t.scheduleHint, members: memberCount(team.memberCount, language), statistics: t.statisticsHint,
  }
  return <>
    <BackLink href={routeHash({ page: 'team' })} label={t.back} />
    <h1>{team.name}</h1>
    <nav aria-label={t.parts} className="card team-list">
      <ul className="rows">{teamSections.map(section => <li key={section}>
        <a className="row team-row" href={routeHash({ page: 'team', teamId: team.id, section })}>
          <span className="event-text"><b>{t[section]}</b><span className="muted">{hint[section]}</span></span>
          <span className="chevron" aria-hidden="true">›</span>
        </a></li>)}</ul>
    </nav>
    <ManageTeam key={team.name} team={team} language={language} csrfToken={csrfToken} onRenamed={onChanged} onDenied={onDenied} />
  </>
}

/**
 * #/team/<teamId> and its parts (#/team/<teamId>/schedule, /members, /statistics). Each part loads only its own
 * data; the small team list read gives the name for the heading and the way back.
 */
export function TeamPage({ teamId, section, language, csrfToken }: {
  teamId: string; section?: TeamSection; language: Language; csrfToken: string
}) {
  const t = words[language]
  const [team, setTeam] = useState<Team | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'gone' | 'error'>('loading')
  const [revision, setRevision] = useState(0)
  const [statsRange, setStatsRange] = useState<{ from: string; to: string } | null>(null)

  useEffect(() => {
    let active = true
    readTeams().then(teams => {
      if (!active) return
      const found = teams === 'denied' ? undefined : teams.find(item => item.id === teamId)
      setTeam(found ?? null)
      setState(found ? 'ready' : 'gone')
    }).catch(() => { if (active) setState('error') })
    return () => { active = false }
  }, [teamId, revision])

  const gone = () => { setTeam(null); setState('gone') }
  if (state === 'loading' && !team) return <p role="status">{t.loading}</p>
  if (state === 'error') return <>
    <BackLink href={routeHash({ page: 'team' })} label={t.back} />
    <p role="alert">{t.error}</p>
    <button type="button" onClick={() => { setState('loading'); setRevision(value => value + 1) }}>{t.retry}</button>
  </>
  if (state === 'gone' || !team) return <>
    <BackLink href={routeHash({ page: 'team' })} label={t.back} />
    <p>{t.gone}</p>
  </>
  if (!section) return <TeamHome team={team} language={language} csrfToken={csrfToken}
    onChanged={() => setRevision(value => value + 1)} onDenied={gone} />
  return <>
    <BackLink href={routeHash({ page: 'team', teamId })} label={team.name} />
    <h1>{t[section]}</h1>
    <nav aria-label={t.parts} className="admin-nav"><ul>
      {teamSections.map(item => <li key={item}><a href={routeHash({ page: 'team', teamId, section: item })}
        aria-current={item === section ? 'page' : undefined}>{t[item]}</a></li>)}
    </ul></nav>
    {section === 'schedule' && <TeamSchedule teamId={teamId} teamName={team.name} language={language} csrfToken={csrfToken} />}
    {section === 'members' && <TeamMembers teamId={teamId} language={language} csrfToken={csrfToken} onChanged={() => undefined} />}
    {section === 'statistics' && <>
      <AttendanceStatistics teamId={teamId} teamName={team.name} language={language} onRange={setStatsRange} />
      <JsExport teamId={teamId} language={language} range={statsRange} />
    </>}
  </>
}
