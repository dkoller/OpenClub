import { useEffect, useRef, useState, type FormEvent } from 'react'
import type { Language } from './language'
import { fetchMemberPage, type Member } from './members'
import { fetchRoleHolders, latestLookup, readRole, saveRole, type Role, type RoleHolder } from './roles'

const words = {
  de: { holders: 'Administratorinnen und Administratoren', change: 'Rolle ändern', other: 'Ein anderes Mitglied', title: 'Vereinsrollen verwalten', search: 'Mitglied suchen', find: 'Suchen', member: 'Mitglied', role: 'Rolle', save: 'Rolle speichern', admin: 'Administrator', regular: 'Mitglied', error: 'Rollenverwaltung nicht verfügbar.', empty: 'Keine Mitglieder gefunden.' },
  en: { holders: 'Administrators', change: 'Change role', other: 'Another member', title: 'Manage club roles', search: 'Find member', find: 'Search', member: 'Member', role: 'Role', save: 'Save role', admin: 'Administrator', regular: 'Member', error: 'Role management unavailable.', empty: 'No members found.' },
  fr: { holders: 'Administratrices et administrateurs', change: 'Changer le rôle', other: 'Un autre membre', title: 'Gérer les rôles du club', search: 'Chercher un membre', find: 'Chercher', member: 'Membre', role: 'Rôle', save: 'Enregistrer le rôle', admin: 'Administrateur', regular: 'Membre', error: 'Gestion des rôles indisponible.', empty: 'Aucun membre trouvé.' },
  it: { holders: 'Amministratrici e amministratori', change: 'Cambia ruolo', other: 'Un altro membro', title: 'Gestisci ruoli del club', search: 'Cerca membro', find: 'Cerca', member: 'Membro', role: 'Ruolo', save: 'Salva ruolo', admin: 'Amministratore', regular: 'Membro', error: 'Gestione ruoli non disponibile.', empty: 'Nessun membro trovato.' },
} satisfies Record<Language, Record<string, string>>

export function RoleAdmin({ language, csrfToken }: { language: Language; csrfToken: string }) {
  const t = words[language]
  const [query, setQuery] = useState('')
  const [members, setMembers] = useState<Member[] | null>(null)
  const [memberId, setMemberId] = useState('')
  const [current, setCurrent] = useState<Role | null>(null)
  const [chosen, setChosen] = useState<Role>('MEMBER')
  const [error, setError] = useState(false)
  const [denied, setDenied] = useState(false)
  const [busy, setBusy] = useState(false)
  const [holders, setHolders] = useState<RoleHolder[] | null>(null)
  const lookups = useRef(latestLookup())
  useEffect(() => () => lookups.current.cancel(), [])
  useEffect(() => { void loadHolders() }, [])

  async function loadHolders() {
    try {
      const result = await fetchRoleHolders()
      if (result === 'denied') { setDenied(true); return }
      setHolders(result.items)
    } catch { setError(true) }
  }

  function changeHolder(holder: RoleHolder) {
    setMembers([{ id: holder.memberId, firstName: holder.firstName, lastName: holder.lastName }])
    void loadRole(holder.memberId, lookups.current.next())
  }

  async function loadRole(id: string, signal: AbortSignal) {
    setMemberId(id)
    setCurrent(null)
    if (!id) return
    try {
      const role = await readRole(id, signal)
      if (signal.aborted) return
      if (role === 'denied') { setDenied(true); return }
      setCurrent(role)
      setChosen(role)
    } catch { if (!signal.aborted) setError(true) }
  }

  async function find(event: FormEvent) {
    event.preventDefault()
    const signal = lookups.current.next()
    setError(false)
    setMembers(null)
    setMemberId('')
    setCurrent(null)
    try {
      const page = await fetchMemberPage(query, undefined, signal)
      if (signal.aborted) return
      if (page === 'denied') { setDenied(true); return }
      setMembers(page.items)
      await loadRole(page.items[0]?.id ?? '', signal)
    } catch { if (!signal.aborted) setError(true) }
  }

  async function save(event: FormEvent) {
    event.preventDefault()
    if (!memberId || !current) return
    setBusy(true)
    setError(false)
    try {
      if (await saveRole(memberId, chosen, csrfToken) === 'denied') { setDenied(true); return }
      await loadRole(memberId, lookups.current.next())
      void loadHolders()
    } catch { setError(true) }
    finally { setBusy(false) }
  }

  if (denied) return null
  return (
    <section aria-label={t.title} className="stack">
      {/* Who holds a role today comes first; the search below is for giving one to someone else. */}
      <h2>{t.holders}</h2>
      {holders !== null && <ul className="rows card">{holders.map(holder => <li key={holder.memberId} className="row pad">
        <span className="event-text"><b>{holder.firstName} {holder.lastName}</b>
          <span className="muted small">{holder.role === 'ADMIN' ? t.admin : t.regular}</span></span>
        <button type="button" className="btn" disabled={busy} aria-label={`${t.change}: ${holder.firstName} ${holder.lastName}`}
          onClick={() => changeHolder(holder)}>{t.change}</button>
      </li>)}</ul>}
      <h2>{t.other}</h2>
      <form className="search" onSubmit={event => { void find(event) }}>
        <label htmlFor="role-search">{t.search}</label>
        <input id="role-search" maxLength={100} value={query} onChange={event => setQuery(event.target.value)} />
        <button type="submit" disabled={busy}>{t.find}</button>
      </form>
      {members?.length === 0 && <p>{t.empty}</p>}
      {members && members.length > 0 && <form className="form-grid" onSubmit={event => { void save(event) }}>
        <label>{t.member}
          <select value={memberId} disabled={busy} onChange={event => {
            void loadRole(event.target.value, lookups.current.next())
          }}>
            {members.map(member => <option key={member.id} value={member.id}>
              {member.firstName} {member.lastName}
            </option>)}
          </select>
        </label>
        <label>{t.role}
          <select value={chosen} disabled={current === null || busy}
            onChange={event => setChosen(event.target.value as Role)}>
            <option value="MEMBER">{t.regular}</option>
            <option value="ADMIN">{t.admin}</option>
          </select>
        </label>
        <button type="submit" disabled={current === null || busy || current === chosen}>{t.save}</button>
      </form>}
      {error && <p role="alert">{t.error}</p>}
    </section>
  )
}
