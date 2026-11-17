import { useEffect, useRef, useState, type FormEvent } from 'react'
import type { Language } from './language'
import { fetchMemberPage, type Member } from './members'
import { latestLookup, readRole, saveRole, type Role } from './roles'

const words = {
  de: { title: 'Vereinsrollen verwalten', search: 'Mitglied suchen', find: 'Suchen', member: 'Mitglied', role: 'Rolle', save: 'Rolle speichern', admin: 'Administrator', regular: 'Mitglied', error: 'Rollenverwaltung nicht verfügbar.', empty: 'Keine Mitglieder gefunden.' },
  en: { title: 'Manage club roles', search: 'Find member', find: 'Search', member: 'Member', role: 'Role', save: 'Save role', admin: 'Administrator', regular: 'Member', error: 'Role management unavailable.', empty: 'No members found.' },
  fr: { title: 'Gérer les rôles du club', search: 'Chercher un membre', find: 'Chercher', member: 'Membre', role: 'Rôle', save: 'Enregistrer le rôle', admin: 'Administrateur', regular: 'Membre', error: 'Gestion des rôles indisponible.', empty: 'Aucun membre trouvé.' },
  it: { title: 'Gestisci ruoli del club', search: 'Cerca membro', find: 'Cerca', member: 'Membro', role: 'Ruolo', save: 'Salva ruolo', admin: 'Amministratore', regular: 'Membro', error: 'Gestione ruoli non disponibile.', empty: 'Nessun membro trovato.' },
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
  const lookups = useRef(latestLookup())
  useEffect(() => () => lookups.current.cancel(), [])

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
    } catch { setError(true) }
    finally { setBusy(false) }
  }

  if (denied) return null
  return (
    <section aria-label={t.title}>
      <h2>{t.title}</h2>
      <form onSubmit={event => { void find(event) }}>
        <label>{t.search}
          <input maxLength={100} value={query} onChange={event => setQuery(event.target.value)} />
        </label>
        <button type="submit" disabled={busy}>{t.find}</button>
      </form>
      {members?.length === 0 && <p>{t.empty}</p>}
      {members && members.length > 0 && <form onSubmit={event => { void save(event) }}>
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
