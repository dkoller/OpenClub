import { useCallback, useEffect, useState, type FormEvent } from 'react'
import type { Language } from './language'

type Member = { id: string; firstName: string; lastName: string }
type Page = { items: Member[]; nextCursor?: string }

const strings = {
  de: { heading: 'Mitglieder verwalten', search: 'Name suchen', find: 'Suchen', loading: 'Mitglieder werden geladen…', denied: 'Keine Berechtigung zur Mitgliederverwaltung.', unavailable: 'Mitgliederverwaltung nicht verfügbar.', retry: 'Erneut versuchen', first: 'Vorname', last: 'Nachname', dob: 'Geburtsdatum', email: 'E-Mail', phone: 'Telefon', create: 'Mitglied hinzufügen', edit: 'Bearbeiten', save: 'Speichern', cancel: 'Abbrechen', archive: 'Archivieren', more: 'Weitere Mitglieder', saved: 'Mitglied aktualisiert.', empty: 'Keine Mitglieder gefunden.', problem: 'Aktion fehlgeschlagen.' },
  en: { heading: 'Manage members', search: 'Search names', find: 'Search', loading: 'Loading members…', denied: 'No permission to manage members.', unavailable: 'Member management unavailable.', retry: 'Try again', first: 'First name', last: 'Last name', dob: 'Date of birth', email: 'Email', phone: 'Phone', create: 'Add member', edit: 'Edit', save: 'Save', cancel: 'Cancel', archive: 'Archive', more: 'More members', saved: 'Member updated.', empty: 'No members found.', problem: 'Action failed.' },
  fr: { heading: 'Gérer les membres', search: 'Rechercher un nom', find: 'Rechercher', loading: 'Chargement des membres…', denied: 'Accès à la gestion des membres refusé.', unavailable: 'Gestion des membres indisponible.', retry: 'Réessayer', first: 'Prénom', last: 'Nom', dob: 'Date de naissance', email: 'E-mail', phone: 'Téléphone', create: 'Ajouter un membre', edit: 'Modifier', save: 'Enregistrer', cancel: 'Annuler', archive: 'Archiver', more: 'Plus de membres', saved: 'Membre mis à jour.', empty: 'Aucun membre trouvé.', problem: 'Action échouée.' },
  it: { heading: 'Gestisci membri', search: 'Cerca nomi', find: 'Cerca', loading: 'Caricamento membri…', denied: 'Nessun permesso di gestione membri.', unavailable: 'Gestione membri non disponibile.', retry: 'Riprova', first: 'Nome', last: 'Cognome', dob: 'Data di nascita', email: 'Email', phone: 'Telefono', create: 'Aggiungi membro', edit: 'Modifica', save: 'Salva', cancel: 'Annulla', archive: 'Archivia', more: 'Altri membri', saved: 'Membro aggiornato.', empty: 'Nessun membro trovato.', problem: 'Operazione non riuscita.' },
} satisfies Record<Language, Record<string, string>>

export function MemberAdmin({ language, csrfToken, onChanged }: { language: Language; csrfToken: string; onChanged: () => void }) {
  const t = strings[language]
  const [items, setItems] = useState<Member[] | null>(null)
  const [cursor, setCursor] = useState<string | undefined>()
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const [denied, setDenied] = useState(false)
  const [failure, setFailure] = useState('')
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState<Member | null>(null)
  const [first, setFirst] = useState('')
  const [last, setLast] = useState('')
  const [dob, setDob] = useState('')
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')

  const load = useCallback(async (q: string, next?: string) => {
    try {
      const args = new URLSearchParams({ q, limit: '20' })
      if (next) args.set('cursor', next)
      const response = await fetch('/api/v1/members?' + args, { credentials: 'same-origin', cache: 'no-store' })
      if (response.status === 403) { setDenied(true); return }
      if (!response.ok) throw new Error('Search failed')
      const page: Page = await response.json()
      if (!Array.isArray(page.items)) throw new Error('Invalid directory')
      setItems(previous => next ? [...(previous ?? []), ...page.items] : page.items)
      setCursor(page.nextCursor)
      setFailure('')
    } catch {
      setFailure(t.unavailable)
    }
  }, [t.unavailable])

  useEffect(() => { void load('') }, []) // Refresh after each write, search or language change below.

  async function mutation(path: string, method: string, body?: object) {
    setBusy(true)
    setFailure('')
    try {
      const response = await fetch(path, {
        method,
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
        ...(body ? { body: JSON.stringify(body) } : {}),
      })
      if (!response.ok) {
        if (response.status === 403) setDenied(true)
        throw new Error('Request rejected')
      }
      setEditing(null)
      await load(query)
      onChanged()
      setFailure('')
      if (method === 'POST' && path === '/api/v1/members') {
        setFirst(''); setLast(''); setDob(''); setEmail(''); setPhone('')
      }
    } catch {
      setFailure(t.problem)
    } finally {
      setBusy(false)
    }
  }

  function submitSearch(event: FormEvent) {
    event.preventDefault()
    setQuery(search)
    setItems(null)
    void load(search)
  }

  if (denied) return <section aria-label={t.heading}><h2>{t.heading}</h2><p role="alert">{t.denied}</p></section>

  return (
    <section aria-labelledby="member-admin-heading">
      <h2 id="member-admin-heading">{t.heading}</h2>
      <form onSubmit={submitSearch}>
        <label htmlFor="member-search">{t.search}</label>
        <input id="member-search" value={search} maxLength={100} onChange={e => setSearch(e.target.value)} />
        <button type="submit">{t.find}</button>
      </form>
      {failure && <p role="alert">{failure} <button type="button" onClick={() => { void load(query) }}>{t.retry}</button></p>}
      {items === null ? <p>{t.loading}</p> : items.length === 0 ? <p>{t.empty}</p> : (
        <ul>
          {items.map(member => (
            <li key={member.id}>
              {editing?.id === member.id ? (
                <form onSubmit={event => {
                  event.preventDefault()
                  void mutation('/api/v1/members/' + member.id, 'PATCH', {
                    firstName: editing.firstName, lastName: editing.lastName,
                  })
                }}>
                  <label>{t.first}<input value={editing.firstName} required maxLength={200} onChange={e => setEditing({ ...editing, firstName: e.target.value })} /></label>
                  <label>{t.last}<input value={editing.lastName} required maxLength={200} onChange={e => setEditing({ ...editing, lastName: e.target.value })} /></label>
                  <button disabled={busy} type="submit">{t.save}</button>
                  <button type="button" onClick={() => setEditing(null)}>{t.cancel}</button>
                </form>
              ) : (
                <>{member.firstName} {member.lastName}{' '}
                  <button type="button" disabled={busy} onClick={() => setEditing(member)}>{t.edit}</button>
                  <button type="button" disabled={busy} onClick={() => {
                    void mutation('/api/v1/members/' + member.id + '/archive', 'POST')
                  }}>{t.archive}</button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {cursor && <button type="button" disabled={busy} onClick={() => { void load(query, cursor) }}>{t.more}</button>}
      <form onSubmit={event => {
        event.preventDefault()
        void mutation('/api/v1/members', 'POST', {
          firstName: first, lastName: last, dateOfBirth: dob,
          ...(email ? { email } : {}), ...(phone ? { phone } : {}),
        })
      }}>
        <label>{t.first}<input value={first} required maxLength={200} onChange={e => setFirst(e.target.value)} /></label>
        <label>{t.last}<input value={last} required maxLength={200} onChange={e => setLast(e.target.value)} /></label>
        <label>{t.dob}<input type="date" value={dob} required onChange={e => setDob(e.target.value)} /></label>
        <label>{t.email}<input type="email" value={email} onChange={e => setEmail(e.target.value)} /></label>
        <label>{t.phone}<input type="tel" value={phone} onChange={e => setPhone(e.target.value)} /></label>
        <button type="submit" disabled={busy}>{t.create}</button>
      </form>
    </section>
  )
}
