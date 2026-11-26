import { useEffect, useRef, useState, type FormEvent } from 'react'
import type { Language } from './language'
import { writeJson } from './api'
import { capabilityChoices, capabilityForScope, capabilityLabel, fetchGrantOverview, fetchGrants, grantChange,
  grantLabel, probeGrantAccess, type Capability, type Grant, type GrantHolder, type Scope } from './grants'
import { fetchMemberPage, type Member } from './members'

type Team = { id: string; name: string }
const words = {
  de: { overview: 'Wer darf was', nobody: 'Noch niemand hat eine Berechtigung.', change: 'Ändern', title: 'Berechtigungen verwalten', search: 'Mitglied suchen', find: 'Suchen', member: 'Mitglied', scope: 'Bereich', club: 'Verein', team: 'Team', capability: 'Berechtigung', grant: 'Erteilen', revoke: 'Entziehen', existing: 'Aktive Berechtigungen', empty: 'Keine Berechtigungen.', error: 'Berechtigungen nicht verfügbar.', retry: 'Erneut versuchen', select: 'Bitte Mitglied auswählen.' },
  en: { overview: 'Who can do what', nobody: 'Nobody has a permission yet.', change: 'Change', title: 'Manage permissions', search: 'Find member', find: 'Search', member: 'Member', scope: 'Scope', club: 'Club', team: 'Team', capability: 'Capability', grant: 'Grant', revoke: 'Revoke', existing: 'Current grants', empty: 'No grants.', error: 'Permissions unavailable.', retry: 'Try again', select: 'Select a member.' },
  fr: { overview: 'Qui peut faire quoi', nobody: 'Personne n’a encore d’autorisation.', change: 'Modifier', title: 'Gérer les autorisations', search: 'Chercher un membre', find: 'Chercher', member: 'Membre', scope: 'Portée', club: 'Club', team: 'Équipe', capability: 'Autorisation', grant: 'Accorder', revoke: 'Révoquer', existing: 'Autorisations actuelles', empty: 'Aucune autorisation.', error: 'Autorisations indisponibles.', retry: 'Réessayer', select: 'Choisir un membre.' },
  it: { overview: 'Chi può fare cosa', nobody: 'Nessuno ha ancora un permesso.', change: 'Modifica', title: 'Gestisci permessi', search: 'Cerca membro', find: 'Cerca', member: 'Membro', scope: 'Ambito', club: 'Club', team: 'Squadra', capability: 'Permesso', grant: 'Concedi', revoke: 'Revoca', existing: 'Permessi attivi', empty: 'Nessun permesso.', error: 'Permessi non disponibili.', retry: 'Riprova', select: 'Seleziona un membro.' },
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
  const [scope, setScope] = useState<Scope>('CLUB')
  const [teamId, setTeamId] = useState('')
  const [capability, setCapability] = useState<Capability>('VIEW_TEAMS')
  const [error, setError] = useState(false)
  const [busy, setBusy] = useState(false)
  const [retryCount, setRetryCount] = useState(0)
  const [holders, setHolders] = useState<GrantHolder[] | null>(null)
  const editor = useRef<HTMLElement | null>(null)
  const memberLookupRequest = useRef<AbortController | null>(null)
  useEffect(() => () => memberLookupRequest.current?.abort(), [])

  useEffect(() => {
    let active = true
    void (async () => {
      try {
        const access = await probeGrantAccess()
        if (!active) return
        if (access === 'denied') { setDenied(true); return }
        setAllowed(true)
        setError(false)
        void loadOverview()
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
  }, [retryCount])

  async function loadOverview() {
    try {
      const result = await fetchGrantOverview()
      if (result === 'denied') { setDenied(true); return }
      setHolders(result.items)
    } catch {
      setError(true)
    }
  }

  function changeHolder(holder: GrantHolder) {
    memberLookupRequest.current?.abort()
    const controller = new AbortController()
    memberLookupRequest.current = controller
    setMembers([{ id: holder.memberId, firstName: holder.firstName, lastName: holder.lastName }])
    setMemberId(holder.memberId)
    setGrants([])
    void loadGrants(holder.memberId, controller.signal)
    editor.current?.scrollIntoView({ block: 'start' })
  }

  async function loadGrants(targetId: string, signal?: AbortSignal) {
    try {
      const result = await fetchGrants(targetId, signal)
      if (signal?.aborted) return
      if (result === 'denied') { setDenied(true); return }
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
      const result = await fetchMemberPage(search, undefined, controller.signal)
      if (controller.signal.aborted) return
      if (result === 'denied') { setDenied(true); return }
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
      if (await writeJson('/api/v1/grants', method, csrfToken, { memberId, ...grant }) === 'denied') {
        setDenied(true)
        return
      }
      await loadGrants(memberId)
      void loadOverview()
    } catch {
      setError(true)
    } finally {
      setBusy(false)
    }
  }

  if (denied) return null
  if (!allowed) return error ? <section aria-label={t.title}>
    <p role="alert">{t.error}</p>
    <button type="button" onClick={() => { setError(false); setRetryCount(value => value + 1) }}>{t.retry}</button>
  </section> : null
  const choices = capabilityChoices(scope)
  const teamName = (grant: Grant & { teamName?: string }) =>
    grant.teamName ?? teams.find(team => team.id === grant.teamId)?.name
  return (<>
    <section aria-label={t.overview}>
      <h2>{t.overview}</h2>
      {holders !== null && (holders.length === 0 ? <p>{t.nobody}</p>
        : <ul className="member-rows grant-overview">{holders.map(holder => <li key={holder.memberId}>
          <div>
            <strong>{holder.firstName} {holder.lastName}</strong>
            <ul>{holder.grants.map(grant => <li key={grant.scope + grant.capability + (grant.teamId ?? '')}>
              {grantLabel(grant, language, teamName(grant) ?? t.team)}
            </li>)}</ul>
          </div>
          <button type="button" disabled={busy} aria-label={t.change + ': ' + holder.firstName + ' ' + holder.lastName}
            onClick={() => changeHolder(holder)}>{t.change}</button>
        </li>)}</ul>)}
    </section>
    <section aria-label={t.title} ref={editor}>
      <h2>{t.title}</h2>
      {error && <p role="alert">{t.error}</p>}
      <form onSubmit={event => { void findMember(event) }}>
        <label>{t.search}<input value={search} maxLength={100} onChange={event => setSearch(event.target.value)} /></label>
        <button type="submit" disabled={busy}>{t.find}</button>
      </form>
      {members.length > 0 && <label>{t.member}
        <select value={memberId} disabled={busy} onChange={event => {
          memberLookupRequest.current?.abort()
          const controller = new AbortController()
          memberLookupRequest.current = controller
          setMemberId(event.target.value)
          setGrants([])
          void loadGrants(event.target.value, controller.signal)
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
            {grant.scope === 'CLUB' ? capabilityLabel(grant.capability, language) + ' · ' + t.club
              : grantLabel(grant, language, teamName(grant) ?? t.team)}
            {' '}<button type="button" disabled={busy} onClick={() => {
              void changeGrant(grant, 'DELETE')
            }}>{t.revoke}</button>
          </li>)}</ul>}
        <form onSubmit={event => {
          event.preventDefault()
          void changeGrant(grantChange(capability, scope, teamId), 'POST')
        }}>
          <label>{t.scope}
            <select value={scope} onChange={event => {
              const next = event.target.value as Scope
              setScope(next)
              setCapability(capabilityForScope(next, capability))
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
              {choices.map(value => <option key={value} value={value}>{capabilityLabel(value, language)}</option>)}
            </select>
          </label>
          <button type="submit" disabled={busy || (scope === 'TEAM' && !teamId)}>{t.grant}</button>
        </form>
      </> : <p>{t.select}</p>}
    </section>
  </>)
}
