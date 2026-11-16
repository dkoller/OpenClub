import { useState, type FormEvent } from 'react'
import type { Language } from './language'

type Member = { id: string; firstName: string; lastName: string }
type Role = 'ADMIN' | 'MEMBER'

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

  async function loadRole(id: string) {
    setMemberId(id)
    setCurrent(null)
    if (!id) return
    try {
      const response = await fetch('/api/v1/members/' + encodeURIComponent(id) + '/role',
        { credentials: 'same-origin', cache: 'no-store' })
      if (response.status === 403) { setDenied(true); return }
      if (!response.ok) throw new Error('Role unavailable')
      const value: unknown = await response.json()
      if (!value || typeof value !== 'object' || !('role' in value) ||
          (value.role !== 'ADMIN' && value.role !== 'MEMBER')) throw new Error('Invalid role')
      setCurrent(value.role)
      setChosen(value.role)
    } catch { setError(true) }
  }

  async function find(event: FormEvent) {
    event.preventDefault()
    setError(false)
    setMembers(null)
    try {
      const response = await fetch('/api/v1/members?' + new URLSearchParams({ q: query, limit: '20' }),
        { credentials: 'same-origin', cache: 'no-store' })
      if (response.status === 403) { setDenied(true); return }
      if (!response.ok) throw new Error('Search unavailable')
      const value: { items?: Member[] } = await response.json()
      if (!Array.isArray(value.items)) throw new Error('Invalid results')
      setMembers(value.items)
      await loadRole(value.items[0]?.id ?? '')
    } catch { setError(true) }
  }

  async function save(event: FormEvent) {
    event.preventDefault()
    if (!memberId || !current) return
    setBusy(true)
    setError(false)
    try {
      const response = await fetch('/api/v1/members/' + encodeURIComponent(memberId) + '/role', {
        method: 'PATCH',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
        body: JSON.stringify({ role: chosen }),
      })
      if (response.status === 403) { setError(true); return }
      if (!response.ok) throw new Error('Role update rejected')
      await loadRole(memberId)
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
          <select value={memberId} onChange={event => { void loadRole(event.target.value) }}>
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
