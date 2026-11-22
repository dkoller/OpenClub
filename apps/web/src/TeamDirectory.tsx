import { useEffect, useState, type FormEvent } from 'react'
import type { Language } from './language'
import { routeHash } from './routes'
import { createTeam as requestCreateTeam, readTeams, type Team } from './teams'

const words = {
  de: { title: 'Teams', loading: 'Teams werden geladen…', error: 'Teams nicht verfügbar.', retry: 'Erneut versuchen', empty: 'Keine Teams vorhanden.', one: '1 Mitglied', many: '{n} Mitglieder', name: 'Teamname', create: 'Team hinzufügen', createError: 'Team konnte nicht erstellt werden.' },
  en: { title: 'Teams', loading: 'Loading teams…', error: 'Teams unavailable.', retry: 'Try again', empty: 'No teams yet.', one: '1 member', many: '{n} members', name: 'Team name', create: 'Add team', createError: 'Could not create team.' },
  fr: { title: 'Équipes', loading: 'Chargement des équipes…', error: 'Équipes indisponibles.', retry: 'Réessayer', empty: 'Aucune équipe.', one: '1 membre', many: '{n} membres', name: 'Nom de l’équipe', create: 'Ajouter une équipe', createError: 'Impossible de créer l’équipe.' },
  it: { title: 'Squadre', loading: 'Caricamento squadre…', error: 'Squadre non disponibili.', retry: 'Riprova', empty: 'Nessuna squadra.', one: '1 membro', many: '{n} membri', name: 'Nome squadra', create: 'Aggiungi squadra', createError: 'Impossibile creare la squadra.' },
} satisfies Record<Language, Record<string, string>>

/** "1 member", "12 members" in the given language. */
export function memberCount(count: number, language: Language): string {
  const t = words[language]
  return count === 1 ? t.one : t.many.replace('{n}', String(count))
}

/**
 * The teams this person sees, one large link per team to its own page, and the form to add a team for people who
 * may (after a refused add, the form disappears). Without any team grant the section is not shown at all.
 */
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
        const teams = await readTeams()
        if (!active) return
        if (teams === 'denied') { setDenied(true); return }
        setTeams(teams)
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
      const outcome = await requestCreateTeam(name, csrfToken)
      if (outcome === 'unauthenticated') { setDenied(true); return }
      if (outcome === 'forbidden') { setCreateDenied(true); return }
      if (outcome !== 'ok') throw new Error('Team create rejected')
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
          : <div className="card team-list"><ul className="rows">{teams.map(team => <li key={team.id}>
            <a className="row team-row" href={routeHash({ page: 'team', teamId: team.id })}>
              <span className="event-text"><b>{team.name}</b><span className="muted">{memberCount(team.memberCount, language)}</span></span>
              <span className="chevron" aria-hidden="true">›</span>
            </a>
          </li>)}</ul></div>}
      {!createDenied && <form className="team-create" onSubmit={event => { void createTeam(event) }}>
        <label>{t.name}<input value={name} maxLength={200} required onChange={event => setName(event.target.value)} /></label>
        <button type="submit" disabled={creating}>{t.create}</button>
      </form>}
      {createError && <p role="alert">{t.createError}</p>}
    </section>
  )
}
