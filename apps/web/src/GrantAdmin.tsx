import { useEffect, useRef, useState, type FormEvent } from 'react'
import type { Language } from './language'

type Member = { id: string; firstName: string; lastName: string }
type Team = { id: string; name: string }
type Grant = { capability: string; scope: 'CLUB' | 'TEAM'; teamId?: string }
type Capability = 'VIEW_MEMBER_DIRECTORY' | 'EDIT_MEMBERS' | 'VIEW_TEAMS' | 'MANAGE_TEAMS' | 'MANAGE_PERMISSIONS'
const capabilities: Capability[] = ['VIEW_MEMBER_DIRECTORY', 'EDIT_MEMBERS', 'VIEW_TEAMS', 'MANAGE_TEAMS', 'MANAGE_PERMISSIONS']
const words = {
  de: { title: 'Berechtigungen verwalten', search: 'Mitglied suchen', find: 'Suchen', member: 'Mitglied', scope: 'Bereich', club: 'Verein', team: 'Team', capability: 'Berechtigung', grant: 'Erteilen', revoke: 'Entziehen', existing: 'Aktive Berechtigungen', empty: 'Keine Berechtigungen.', error: 'Berechtigungen nicht verfügbar.', select: 'Bitte Mitglied auswählen.' },
  en: { title: 'Manage permissions', search: 'Find member', find: 'Search', member: 'Member', scope: 'Scope', club: 'Club', team: 'Team', capability: 'Capability', grant: 'Grant', revoke: 'Revoke', existing: 'Current grants', empty: 'No grants.', error: 'Permissions unavailable.', select: 'Select a member.' },
  fr: { title: 'Gérer les autorisations', search: 'Chercher un membre', find: 'Chercher', member: 'Membre', scope: 'Portée', club: 'Club', team: 'Équipe', capability: 'Autorisation', grant: 'Accorder', revoke: 'Révoquer', existing: 'Autorisations actuelles', empty: 'Aucune autorisation.', error: 'Autorisations indisponibles.', select: 'Choisir un membre.' },
  it: { title: 'Gestisci permessi', search: 'Cerca membro', find: 'Cerca', member: 'Membro', scope: 'Ambito', club: 'Club', team: 'Squadra', capability: 'Permesso', grant: 'Concedi', revoke: 'Revoca', existing: 'Permessi attivi', empty: 'Nessun permesso.', error: 'Permessi non disponibili.', select: 'Seleziona un membro.' },
} satisfies Record<Language, Record<string, string>>

export function GrantAdmin({ language, csrfToken }: { language: Language; csrfToken: string }) {
  const t = words[language]
  const [allowed, setAllowed] = useState(false)
  const [denied, setDenied] = useState(false)
  const [members, setMembers] = useState<Member[]>([])
  const [teams, setTeams] = useState<Team[]>([])
  const [search, setSearch] = useState('')
  const [memberId, setMemberId] = useState('')
  const [grants, setGrants] = useState<Grant[]>([])
  const [scope, setScope] = useState<'CLUB' | 'TEAM'>('CLUB')
  const [teamId, setTeamId] = useState('')
  const [capability, setCapability] = useState<Capability>('VIEW_TEAMS')
  const [error, setError] = useState(false)
  const [busy, setBusy] = useState(false)
  const memberLookupRequest = useRef<AbortController | null>(null)
  useEffect(() => () => memberLookupRequest.current?.abort(), [])

  useEffect(() => {
    let active = true
    void (async () => {
      try {
        const response = await fetch('/api/v1/grants?memberId=00000000-0000-0000-0000-000000000000',
          { credentials: 'same-origin', cache: 'no-store' })
        if (!active) return
        if (response.status === 403) { setDenied(true); return }
        if (!response.ok) throw new Error('Grant access unavailable')
        setAllowed(true)
        const result = await fetch('/api/v1/teams', { credentials: 'same-origin', cache: 'no-store' })
        if (result.ok) {
          const page: { items: Team[] } = await result.json()
          if (active && Array.isArray(page.items)) setTeams(page.items)
        }
      } catch {
        if (active) setError(true)
      }
    })()
    return () => { active = false }
  }, [])

  async function loadGrants(targetId: string, signal?: AbortSignal) {
    try {
      const response = await fetch('/api/v1/grants?memberId=' + encodeURIComponent(targetId),
        { credentials: 'same-origin', cache: 'no-store', signal })
      if (signal?.aborted) return
      if (!response.ok) throw new Error('Read denied')
      const result: { items: Grant[] } = await response.json()
      if (signal?.aborted) return
      if (!Array.isArray(result.items)) throw new Error('Invalid grants')
      setGrants(result.items)
      setError(false)
    } catch {
      if (!signal?.aborted) setError(true)
    }
  }

  async function findMember(event: FormEvent) {
    event.preventDefault()
    memberLookupRequest.current?.abort()
    const controller = new AbortController()
    memberLookupRequest.current = controller
    setError(false)
    setMembers([])
    setMemberId('')
    setGrants([])
    try {
      const response = await fetch('/api/v1/members?' + new URLSearchParams({ q: search, limit: '20' }),
        { credentials: 'same-origin', cache: 'no-store', signal: controller.signal })
      if (controller.signal.aborted) return
      if (!response.ok) throw new Error('Search denied')
      const result: { items: Member[] } = await response.json()
      if (controller.signal.aborted) return
      if (!Array.isArray(result.items)) throw new Error('Invalid members')
      setMembers(result.items)
      setMemberId(result.items[0]?.id ?? '')
      if (result.items[0]) await loadGrants(result.items[0].id, controller.signal)
    } catch {
      if (!controller.signal.aborted) setError(true)
    } finally {
      if (memberLookupRequest.current === controller) memberLookupRequest.current = null
    }
  }

  async function changeGrant(grant: Grant, method: 'POST' | 'DELETE') {
    if (!memberId) return
    setBusy(true)
    setError(false)
    try {
      const response = await fetch('/api/v1/grants', {
        method,
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
        body: JSON.stringify({ memberId, ...grant }),
      })
      if (!response.ok) throw new Error('Grant change denied')
      await loadGrants(memberId)
    } catch {
      setError(true)
    } finally {
      setBusy(false)
    }
  }

  if (denied) return null
  if (!allowed) return error ? <p role="alert">{t.error}</p> : null
  const choices = scope === 'TEAM' ? capabilities.filter(value =>
    value === 'VIEW_TEAMS' || value === 'MANAGE_TEAMS') : capabilities
  return (
    <section aria-label={t.title}>
      <h2>{t.title}</h2>
      {error && <p role="alert">{t.error}</p>}
      <form onSubmit={event => { void findMember(event) }}>
        <label>{t.search}<input value={search} maxLength={100} onChange={event => setSearch(event.target.value)} /></label>
        <button type="submit">{t.find}</button>
      </form>
      {members.length > 0 && <label>{t.member}
        <select value={memberId} onChange={event => {
          setMemberId(event.target.value)
          void loadGrants(event.target.value)
        }}>
          {members.map(member => <option key={member.id} value={member.id}>
            {member.firstName} {member.lastName}
          </option>)}
        </select>
      </label>}
      {memberId ? <>
        <h3>{t.existing}</h3>
        {grants.length === 0 ? <p>{t.empty}</p>
          : <ul>{grants.map(grant => <li key={grant.scope + grant.capability + (grant.teamId ?? '')}>
            {grant.capability} · {grant.scope === 'CLUB' ? t.club
              : teams.find(team => team.id === grant.teamId)?.name ?? t.team}
            {' '}<button type="button" disabled={busy} onClick={() => {
              void changeGrant(grant, 'DELETE')
            }}>{t.revoke}</button>
          </li>)}</ul>}
        <form onSubmit={event => {
          event.preventDefault()
          void changeGrant({ capability, scope, ...(scope === 'TEAM' ? { teamId } : {}) }, 'POST')
        }}>
          <label>{t.scope}
            <select value={scope} onChange={event => {
              const next = event.target.value as 'CLUB' | 'TEAM'
              setScope(next)
              if (next === 'TEAM' && !['VIEW_TEAMS', 'MANAGE_TEAMS'].includes(capability)) {
                setCapability('VIEW_TEAMS')
              }
            }}>
              <option value="CLUB">{t.club}</option><option value="TEAM">{t.team}</option>
            </select>
          </label>
          {scope === 'TEAM' && <label>{t.team}
            <select value={teamId} required onChange={event => setTeamId(event.target.value)}>
              <option value="">{t.team}</option>
              {teams.map(team => <option key={team.id} value={team.id}>{team.name}</option>)}
            </select>
          </label>}
          <label>{t.capability}
            <select value={capability} onChange={event => setCapability(event.target.value as Capability)}>
              {choices.map(value => <option key={value} value={value}>{value}</option>)}
            </select>
          </label>
          <button type="submit" disabled={busy || (scope === 'TEAM' && !teamId)}>{t.grant}</button>
        </form>
      </> : <p>{t.select}</p>}
    </section>
  )
}
