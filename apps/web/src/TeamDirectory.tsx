import { useEffect, useState, type FormEvent } from 'react'
import type { Language } from './language'

type Team = { id: string; name: string; memberCount: number }
const words = {
  de: { title: 'Teams', loading: 'Teams werden geladen…', error: 'Teams nicht verfügbar.', retry: 'Erneut versuchen', empty: 'Keine Teams vorhanden.', count: 'Mitglieder' },
  en: { title: 'Teams', loading: 'Loading teams…', error: 'Teams unavailable.', retry: 'Try again', empty: 'No teams yet.', count: 'Members' },
  fr: { title: 'Équipes', loading: 'Chargement des équipes…', error: 'Équipes indisponibles.', retry: 'Réessayer', empty: 'Aucune équipe.', count: 'Membres' },
  it: { title: 'Squadre', loading: 'Caricamento squadre…', error: 'Squadre non disponibili.', retry: 'Riprova', empty: 'Nessuna squadra.', count: 'Membri' },
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

  // Users without a team grant do not see an empty, misleading directory.
  if (denied) return null
  return (
    <section aria-label={t.title}>
      <h2>{t.title}</h2>
      {error ? <><p role="alert">{t.error}</p><button type="button" onClick={() => {
        setError(false); setTeams(null); setRevision(value => value + 1)
      }}>{t.retry}</button></> : teams === null ? <p role="status">{t.loading}</p>
        : teams.length === 0 ? <p>{t.empty}</p>
          : <ul>{teams.map(team => <li key={team.id}>{team.name} — {team.memberCount} {t.count}</li>)}</ul>}
      {!createDenied && <form onSubmit={event => { void createTeam(event) }}>
        <label>{t.name}<input value={name} maxLength={200} required onChange={event => setName(event.target.value)} /></label>
        <button type="submit" disabled={creating}>{t.create}</button>
      </form>}
      {createError && <p role="alert">{t.createError}</p>}
    </section>
  )
}
