import { useEffect, useRef, useState, type FormEvent } from 'react'
import { formatDayTime } from './dates'
import type { Language } from './language'
import { fetchMemberPage, type Member } from './members'
import { latestLookup } from './roles'
import { useEditorAccess } from './editorAccess'
import { probeGrantAccess } from './grants'
import { createInvitation, invitationLink, revokeInvitation, unlinkAccount, type Invitation } from './invitations'

const words = {
  de: { title: 'Einladungen und Kontoverknüpfung', search: 'Mitglied suchen', find: 'Suchen', member: 'Mitglied', create: 'Einladung erstellen', revoke: 'Einladung widerrufen', unlink: 'Konto trennen', link: 'Einladungslink (wird nur einmal angezeigt)', expires: 'Gültig bis', created: 'Einladung erstellt.', revoked: 'Einladung widerrufen.', unlinked: 'Konto getrennt.', conflict: 'Dieses Mitglied kann nicht eingeladen oder getrennt werden (offene Einladung, bereits verknüpft, Administrator, minderjährig oder archiviert).', error: 'Einladungen nicht verfügbar.', empty: 'Keine Mitglieder gefunden.' },
  en: { title: 'Invitations and account links', search: 'Find member', find: 'Search', member: 'Member', create: 'Create invitation', revoke: 'Revoke invitation', unlink: 'Unlink account', link: 'Invitation link (shown only once)', expires: 'Valid until', created: 'Invitation created.', revoked: 'Invitation revoked.', unlinked: 'Account unlinked.', conflict: 'This member cannot be invited or unlinked (open invitation, already linked, administrator, minor or archived).', error: 'Invitations unavailable.', empty: 'No members found.' },
  fr: { title: 'Invitations et liaison de compte', search: 'Chercher un membre', find: 'Chercher', member: 'Membre', create: 'Créer une invitation', revoke: 'Révoquer l’invitation', unlink: 'Dissocier le compte', link: 'Lien d’invitation (affiché une seule fois)', expires: 'Valable jusqu’au', created: 'Invitation créée.', revoked: 'Invitation révoquée.', unlinked: 'Compte dissocié.', conflict: 'Ce membre ne peut pas être invité ou dissocié (invitation ouverte, déjà lié, administrateur, mineur ou archivé).', error: 'Invitations indisponibles.', empty: 'Aucun membre trouvé.' },
  it: { title: 'Inviti e collegamento account', search: 'Cerca membro', find: 'Cerca', member: 'Membro', create: 'Crea invito', revoke: 'Revoca invito', unlink: 'Scollega account', link: 'Link di invito (mostrato una sola volta)', expires: 'Valido fino al', created: 'Invito creato.', revoked: 'Invito revocato.', unlinked: 'Account scollegato.', conflict: 'Questo membro non può essere invitato o scollegato (invito aperto, già collegato, amministratore, minorenne o archiviato).', error: 'Inviti non disponibili.', empty: 'Nessun membro trovato.' },
} satisfies Record<Language, Record<string, string>>

export function InvitationAdmin({ language, csrfToken }: { language: Language; csrfToken: string }) {
  const t = words[language]
  const [query, setQuery] = useState('')
  const [members, setMembers] = useState<Member[] | null>(null)
  const [memberId, setMemberId] = useState('')
  const [invitation, setInvitation] = useState<Invitation | null>(null)
  const [message, setMessage] = useState<'created' | 'revoked' | 'unlinked' | 'conflict' | null>(null)
  const [error, setError] = useState(false)
  const [denied, setDenied] = useState(false)
  // Shown only to those who can use it: editors create and revoke invitations (EDIT_MEMBERS), permission
  // managers unlink accounts (MANAGE_PERMISSIONS). Everyone else never sees the form; the server decides anyway.
  const editor = useEditorAccess()
  const [permissions, setPermissions] = useState<'unknown' | 'allowed' | 'denied'>('unknown')
  const [busy, setBusy] = useState(false)
  const lookups = useRef(latestLookup())
  useEffect(() => () => lookups.current.cancel(), [])
  useEffect(() => {
    let active = true
    probeGrantAccess().then(result => { if (active) setPermissions(result) }).catch(() => { if (active) setPermissions('denied') })
    return () => { active = false }
  }, [])

  function select(id: string) {
    setMemberId(id)
    setInvitation(null)
    setMessage(null)
  }

  async function find(event: FormEvent) {
    event.preventDefault()
    const signal = lookups.current.next()
    setError(false)
    setMembers(null)
    select('')
    try {
      const page = await fetchMemberPage(query, undefined, signal)
      if (signal.aborted) return
      if (page === 'denied') { setDenied(true); return }
      setMembers(page.items)
      select(page.items[0]?.id ?? '')
    } catch { if (!signal.aborted) setError(true) }
  }

  async function act(run: () => Promise<'ok' | 'denied' | 'conflict' | Invitation>, done: 'revoked' | 'unlinked' | 'created') {
    if (!memberId) return
    setBusy(true)
    setError(false)
    setInvitation(null)
    setMessage(null)
    try {
      const result = await run()
      if (result === 'denied') { setDenied(true); return }
      if (result === 'conflict') { setMessage('conflict'); return }
      if (typeof result === 'object') setInvitation(result)
      setMessage(done)
    } catch { setError(true) }
    finally { setBusy(false) }
  }

  if (denied || (editor !== 'editor' && permissions !== 'allowed')) return null
  return (
    <section aria-label={t.title}>
      <h2 className="vh">{t.title}</h2>
      <form className="search" onSubmit={event => { void find(event) }}>
        <label htmlFor="invitation-search">{t.search}</label>
        <input id="invitation-search" maxLength={100} value={query} onChange={event => setQuery(event.target.value)} />
        <button type="submit" disabled={busy}>{t.find}</button>
      </form>
      {members?.length === 0 && <p>{t.empty}</p>}
      {members && members.length > 0 && <div>
        <label>{t.member}
          <select value={memberId} disabled={busy} onChange={event => select(event.target.value)}>
            {members.map(member => <option key={member.id} value={member.id}>
              {member.firstName} {member.lastName}
            </option>)}
          </select>
        </label>
        <button type="button" disabled={busy || !memberId}
          onClick={() => { void act(() => createInvitation(memberId, csrfToken), 'created') }}>{t.create}</button>
        <button type="button" disabled={busy || !memberId}
          onClick={() => { void act(() => revokeInvitation(memberId, csrfToken), 'revoked') }}>{t.revoke}</button>
        <button type="button" disabled={busy || !memberId}
          onClick={() => { void act(() => unlinkAccount(memberId, csrfToken), 'unlinked') }}>{t.unlink}</button>
      </div>}
      {invitation && <p>
        <label>{t.link}
          <input readOnly size={60} value={invitationLink(window.location.origin, invitation.secret)}
            onFocus={event => event.currentTarget.select()} />
        </label>
        {' '}{t.expires} {formatDayTime(invitation.expiresAt, language)}
      </p>}
      {message && <p role="status">{message === 'conflict' ? t.conflict : t[message]}</p>}
      {error && <p role="alert">{t.error}</p>}
    </section>
  )
}
