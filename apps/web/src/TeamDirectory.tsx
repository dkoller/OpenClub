import { useEffect, useState, type FormEvent } from 'react'
import type { Language } from './language'
import { TeamMembers } from './TeamMembers'

type Team = { id: string; name: string; memberCount: number }
const words = {
  de: { title: 'Teams', loading: 'Teams werden geladen…', error: 'Teams nicht verfügbar.', retry: 'Erneut versuchen', empty: 'Keine Teams vorhanden.', count: 'Mitglieder', name: 'Teamname', create: 'Team hinzufügen', createError: 'Team konnte nicht erstellt werden.', rename: 'Umbenennen', save: 'Speichern', cancel: 'Abbrechen', renameError: 'Team konnte nicht umbenannt werden.', delete: 'Team löschen', deleteWarning: 'Team und alle Zuordnungen werden gelöscht. Teamnamen zur Bestätigung eingeben.', confirmDelete: 'Endgültig löschen', deleteError: 'Team konnte nicht gelöscht werden.' },
  en: { title: 'Teams', loading: 'Loading teams…', error: 'Teams unavailable.', retry: 'Try again', empty: 'No teams yet.', count: 'Members', name: 'Team name', create: 'Add team', createError: 'Could not create team.', rename: 'Rename', save: 'Save', cancel: 'Cancel', renameError: 'Could not rename team.', delete: 'Delete team', deleteWarning: 'The team and all assignments will be deleted. Enter its name to confirm.', confirmDelete: 'Delete permanently', deleteError: 'Could not delete team.' },
  fr: { title: 'Équipes', loading: 'Chargement des équipes…', error: 'Équipes indisponibles.', retry: 'Réessayer', empty: 'Aucune équipe.', count: 'Membres', name: 'Nom de l’équipe', create: 'Ajouter une équipe', createError: 'Impossible de créer l’équipe.', rename: 'Renommer', save: 'Enregistrer', cancel: 'Annuler', renameError: 'Impossible de renommer l’équipe.', delete: 'Supprimer l’équipe', deleteWarning: 'L’équipe et toutes ses affectations seront supprimées. Saisissez son nom pour confirmer.', confirmDelete: 'Supprimer définitivement', deleteError: 'Impossible de supprimer l’équipe.' },
  it: { title: 'Squadre', loading: 'Caricamento squadre…', error: 'Squadre non disponibili.', retry: 'Riprova', empty: 'Nessuna squadra.', count: 'Membri', name: 'Nome squadra', create: 'Aggiungi squadra', createError: 'Impossibile creare la squadra.', rename: 'Rinomina', save: 'Salva', cancel: 'Annulla', renameError: 'Impossibile rinominare la squadra.', delete: 'Elimina squadra', deleteWarning: 'La squadra e tutte le assegnazioni saranno eliminate. Inserisci il nome per confermare.', confirmDelete: 'Elimina definitivamente', deleteError: 'Impossibile eliminare la squadra.' },
} satisfies Record<Language, Record<string, string>>

export function TeamDirectory({ language, csrfToken }: { language: Language; csrfToken: string }) {
  const [teams, setTeams] = useState<Team[] | null>(null)
  const [error, setError] = useState(false)
  const [denied, setDenied] = useState(false)
  const [revision, setRevision] = useState(0)
  const [name, setName] = useState('')
  const [creating, setCreating] = useState(false)
  const [createDenied, setCreateDenied] = useState(false)
  const [createError, setCreateError] = useState(false)
  const [editing, setEditing] = useState<Team | null>(null)
  const [renameError, setRenameError] = useState(false)
  const [deleting, setDeleting] = useState<Team | null>(null)
  const [confirmation, setConfirmation] = useState('')
  const [deleteError, setDeleteError] = useState(false)
  const t = words[language]

  useEffect(() => {
    let active = true
    void (async () => {
      try {
        const response = await fetch('/api/v1/teams', { credentials: 'same-origin', cache: 'no-store' })
        if (!active) return
        if (response.status === 403) { setDenied(true); return }
        if (!response.ok) throw new Error('Unavailable')
        const payload: unknown = await response.json()
        if (!payload || typeof payload !== 'object' || !('items' in payload) || !Array.isArray(payload.items)) {
          throw new Error('Unexpected response')
        }
        const items = payload.items
        if (!items.every((item: unknown) => item !== null && typeof item === 'object'
          && 'id' in item && typeof item.id === 'string'
          && 'name' in item && typeof item.name === 'string'
          && 'memberCount' in item && typeof item.memberCount === 'number')) {
          throw new Error('Unexpected response')
        }
        setTeams(items as Team[])
        setError(false)
      } catch {
        if (active) setError(true)
      }
    })()
    return () => { active = false }
  }, [revision])

  async function createTeam(event: FormEvent) {
    event.preventDefault()
    setCreating(true)
    setCreateError(false)
    try {
      const response = await fetch('/api/v1/teams', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
        body: JSON.stringify({ name }),
      })
      if (response.status === 403) { setCreateDenied(true); return }
      if (!response.ok) throw new Error('Team create rejected')
      setName('')
      setTeams(null)
      setRevision(value => value + 1)
    } catch {
      setCreateError(true)
    } finally {
      setCreating(false)
    }
  }

  async function renameTeam(event: FormEvent) {
    event.preventDefault()
    if (!editing) return
    setCreating(true)
    setRenameError(false)
    try {
      const response = await fetch('/api/v1/teams/' + encodeURIComponent(editing.id), {
        method: 'PATCH',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
        body: JSON.stringify({ name: editing.name }),
      })
      if (response.status === 403) { setCreateDenied(true); setEditing(null); return }
      if (!response.ok) throw new Error('Rename rejected')
      setEditing(null)
      setTeams(null)
      setRevision(value => value + 1)
    } catch {
      setRenameError(true)
    } finally {
      setCreating(false)
    }
  }

  async function deleteTeam(event: FormEvent) {
    event.preventDefault()
    if (!deleting || confirmation !== deleting.name) return
    setCreating(true)
    setDeleteError(false)
    try {
      const response = await fetch('/api/v1/teams/' + encodeURIComponent(deleting.id), {
        method: 'DELETE',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
        body: JSON.stringify({ name: confirmation }),
      })
      if (response.status === 403) { setCreateDenied(true); setDeleting(null); return }
      if (!response.ok) throw new Error('Deletion rejected')
      setDeleting(null)
      setConfirmation('')
      setTeams(null)
      setRevision(value => value + 1)
    } catch {
      setDeleteError(true)
    } finally {
      setCreating(false)
    }
  }

  // Users without a team grant do not see an empty, misleading directory.
  if (denied) return null
  return (
    <section aria-label={t.title}>
      <h2>{t.title}</h2>
      {error ? <><p role="alert">{t.error}</p><button type="button" onClick={() => {
        setError(false); setTeams(null); setRevision(value => value + 1)
      }}>{t.retry}</button></> : teams === null ? <p role="status">{t.loading}</p>
        : teams.length === 0 ? <p>{t.empty}</p>
          : <ul>{teams.map(team => <li key={team.id}>
            {editing?.id === team.id ? <form onSubmit={event => { void renameTeam(event) }}>
              <label>{t.name}<input value={editing.name} maxLength={200} required
                onChange={event => setEditing({ ...editing, name: event.target.value })} /></label>
              <button type="submit" disabled={creating}>{t.save}</button>
              <button type="button" disabled={creating} onClick={() => setEditing(null)}>{t.cancel}</button>
            </form> : <>{team.name} — {team.memberCount} {t.count}
              {!createDenied && <button type="button" disabled={creating} onClick={() => {
                setEditing(team); setRenameError(false)
              }}>{t.rename}</button>}
              {!createDenied && <button type="button" disabled={creating} onClick={() => {
                setDeleting(team); setConfirmation(''); setDeleteError(false)
              }}>{t.delete}</button>}
            </>}
            {deleting?.id === team.id && <form onSubmit={event => { void deleteTeam(event) }}>
              <p>{t.deleteWarning}</p>
              <label>{t.name}<input value={confirmation} onChange={event => setConfirmation(event.target.value)}
                required autoComplete="off" /></label>
              <button type="submit" disabled={creating || confirmation !== team.name}>{t.confirmDelete}</button>
              <button type="button" disabled={creating} onClick={() => setDeleting(null)}>{t.cancel}</button>
            </form>}
            <TeamMembers teamId={team.id} language={language} csrfToken={csrfToken}
              onChanged={() => setRevision(value => value + 1)} />
          </li>)}</ul>}
      {!createDenied && <form onSubmit={event => { void createTeam(event) }}>
        <label>{t.name}<input value={name} maxLength={200} required onChange={event => setName(event.target.value)} /></label>
        <button type="submit" disabled={creating}>{t.create}</button>
      </form>}
      {createError && <p role="alert">{t.createError}</p>}
      {renameError && <p role="alert">{t.renameError}</p>}
      {deleteError && <p role="alert">{t.deleteError}</p>}
    </section>
  )
}
