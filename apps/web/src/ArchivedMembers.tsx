import { useEffect, useRef, useState, type FormEvent } from 'react'
import { formatDate } from './dates'
import type { Language } from './language'
import { fetchArchivedPage, type ArchivedMember } from './members'
import { reactivateMember } from './memberDetails'

const words = {
  de: { title: 'Archivierte Mitglieder', search: 'Name suchen', find: 'Suchen', loading: 'Archivierte Mitglieder werden geladen…', empty: 'Keine archivierten Mitglieder gefunden.', archived: 'archiviert am', reactivate: 'Reaktivieren', more: 'Weitere archivierte Mitglieder', reactivated: 'Mitglied reaktiviert (ohne frühere Rechte, Teams oder Anmeldung).', stale: 'Reaktivierung nicht möglich: Der Eintrag erfüllt die aktuellen Vereinsregeln nicht mehr.', gone: 'Dieses Mitglied ist nicht mehr archiviert.', error: 'Archivierte Mitglieder nicht verfügbar.' },
  en: { title: 'Archived members', search: 'Search names', find: 'Search', loading: 'Loading archived members…', empty: 'No archived members found.', archived: 'archived on', reactivate: 'Reactivate', more: 'More archived members', reactivated: 'Member reactivated (without former rights, teams or sign-in).', stale: 'Cannot reactivate: the record no longer meets the club’s current rules.', gone: 'This member is no longer archived.', error: 'Archived members unavailable.' },
  fr: { title: 'Membres archivés', search: 'Rechercher un nom', find: 'Rechercher', loading: 'Chargement des membres archivés…', empty: 'Aucun membre archivé trouvé.', archived: 'archivé le', reactivate: 'Réactiver', more: 'Plus de membres archivés', reactivated: 'Membre réactivé (sans anciens droits, équipes ni connexion).', stale: 'Réactivation impossible : la fiche ne respecte plus les règles actuelles du club.', gone: 'Ce membre n’est plus archivé.', error: 'Membres archivés indisponibles.' },
  it: { title: 'Membri archiviati', search: 'Cerca nomi', find: 'Cerca', loading: 'Caricamento membri archiviati…', empty: 'Nessun membro archiviato trovato.', archived: 'archiviato il', reactivate: 'Riattiva', more: 'Altri membri archiviati', reactivated: 'Membro riattivato (senza diritti, squadre o accesso precedenti).', stale: 'Riattivazione impossibile: la scheda non rispetta più le regole attuali del club.', gone: 'Questo membro non è più archiviato.', error: 'Membri archiviati non disponibili.' },
} satisfies Record<Language, Record<string, string>>

/**
 * Archived members for editors (OpenAPI 1.5.0): find a member archived earlier and reactivate them. Hidden for
 * anyone the server refuses. Erasure stays an operator command in M1 (ADR-0008 decision 21).
 */
export function ArchivedMembers({ language, csrfToken, revision, onChanged }: {
  language: Language; csrfToken: string; revision: number; onChanged: () => void
}) {
  const t = words[language]
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const [items, setItems] = useState<ArchivedMember[] | null>(null)
  const [cursor, setCursor] = useState<string | undefined>()
  const [denied, setDenied] = useState(false)
  const [error, setError] = useState(false)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<'reactivated' | 'stale' | 'gone' | null>(null)
  const active = useRef<AbortController | null>(null)

  async function load(q: string, next?: string) {
    active.current?.abort()
    const controller = new AbortController()
    active.current = controller
    setError(false)
    try {
      const page = await fetchArchivedPage(q, next, controller.signal)
      if (controller.signal.aborted) return
      if (page === 'denied') { setDenied(true); return }
      setItems(previous => next ? [...(previous ?? []), ...page.items] : page.items)
      setCursor(page.nextCursor)
    } catch {
      if (!controller.signal.aborted) { setError(true); setItems(previous => previous ?? []) }
    }
  }

  // Reload when another panel archives someone, and when the query changes.
  useEffect(() => {
    void load(query)
    return () => active.current?.abort()
  }, [query, revision])

  function submit(event: FormEvent) {
    event.preventDefault()
    setNotice(null)
    setItems(null)
    setQuery(search)
  }

  async function reactivate(member: ArchivedMember) {
    setBusy(true)
    setNotice(null)
    try {
      const outcome = await reactivateMember(member.id, csrfToken)
      if (outcome === 'denied') { setDenied(true); return }
      if (outcome === 'conflict') { setNotice('stale'); return }
      if (outcome === 'not-found') { setNotice('gone') } else if (outcome === 'ok') { setNotice('reactivated') } else { setError(true); return }
      await load(query)
      onChanged()
    } catch {
      setError(true)
    } finally {
      setBusy(false)
    }
  }

  if (denied) return null
  return (
    <section aria-label={t.title}>
      <h2 className="vh">{t.title}</h2>
      <form className="search" onSubmit={submit}>
        <label htmlFor="archive-search">{t.search}</label>
        <input id="archive-search" value={search} maxLength={100} onChange={event => setSearch(event.target.value)} />
        <button type="submit">{t.find}</button>
      </form>
      {items === null ? <p role="status">{t.loading}</p>
        : items.length === 0 ? <p>{t.empty}</p>
          : <ul>{items.map(member => <li key={member.id}>
            {member.firstName} {member.lastName}{' '}
            <span>({t.archived} {formatDate(member.archivedAt)})</span>{' '}
            <button type="button" disabled={busy} onClick={() => { void reactivate(member) }}>{t.reactivate}</button>
          </li>)}</ul>}
      {cursor && <button type="button" disabled={busy} onClick={() => { void load(query, cursor) }}>{t.more}</button>}
      {notice && <p role="status">{t[notice]}</p>}
      {error && <p role="alert">{t.error}</p>}
    </section>
  )
}
