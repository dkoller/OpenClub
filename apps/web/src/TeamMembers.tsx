import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import type { Language } from './language'

type Member = { id: string; firstName: string; lastName: string }
type Page = { items: Member[]; nextCursor?: string }

const words = {
  de: { roster: 'Teammitglieder', search: 'Mitglied suchen', find: 'Suchen', add: 'Zuweisen', remove: 'Entfernen', empty: 'Keine Zuweisungen.', more: 'Weitere Mitglieder', error: 'Teamzuweisung fehlgeschlagen.', retry: 'Erneut versuchen', loading: 'Teammitglieder werden geladen…' },
  en: { roster: 'Team members', search: 'Find member', find: 'Search', add: 'Assign', remove: 'Remove', empty: 'No assignments.', more: 'More members', error: 'Team assignment failed.', retry: 'Try again', loading: 'Loading team members…' },
  fr: { roster: 'Membres de l’équipe', search: 'Chercher un membre', find: 'Chercher', add: 'Affecter', remove: 'Retirer', empty: 'Aucune affectation.', more: 'Plus de membres', error: 'Affectation impossible.', retry: 'Réessayer', loading: 'Chargement des membres…' },
  it: { roster: 'Membri della squadra', search: 'Cerca membro', find: 'Cerca', add: 'Assegna', remove: 'Rimuovi', empty: 'Nessuna assegnazione.', more: 'Altri membri', error: 'Assegnazione non riuscita.', retry: 'Riprova', loading: 'Caricamento membri…' },
} satisfies Record<Language, Record<string, string>>

export function TeamMembers({ teamId, language, csrfToken, onChanged }: {
  teamId: string
  language: Language
  csrfToken: string
  onChanged: () => void
}) {
  const t = words[language]
  const [members, setMembers] = useState<Member[] | null>(null)
  const [cursor, setCursor] = useState<string | undefined>()
  const [candidates, setCandidates] = useState<Member[]>([])
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState('')
  const [error, setError] = useState(false)
  const [denied, setDenied] = useState(false)
  const [busy, setBusy] = useState(false)
  const [revision, setRevision] = useState(0)
  const candidateRequest = useRef<AbortController | null>(null)
  const endpoint = '/api/v1/teams/' + encodeURIComponent(teamId) + '/members'

  const load = useCallback(async (next?: string) => {
    try {
      const args = new URLSearchParams({ limit: '20' })
      if (next) args.set('cursor', next)
      const response = await fetch(endpoint + '?' + args, { credentials: 'same-origin', cache: 'no-store' })
      if (response.status === 403) { setDenied(true); return }
      if (!response.ok) throw new Error('Unavailable')
      const page: Page = await response.json()
      if (!Array.isArray(page.items)) throw new Error('Invalid roster')
      setMembers(previous => next ? [...(previous ?? []), ...page.items] : page.items)
      setCursor(page.nextCursor)
      setError(false)
    } catch {
      setError(true)
      setMembers(previous => previous ?? [])
    }
  }, [endpoint])

  useEffect(() => { void load() }, [load, revision])
  useEffect(() => () => candidateRequest.current?.abort(), [])

  async function findMember(event: FormEvent) {
    event.preventDefault()
    candidateRequest.current?.abort()
    const controller = new AbortController()
    candidateRequest.current = controller
    setError(false)
    setCandidates([])
    setSelected('')
    try {
      const args = new URLSearchParams({ q: search, limit: '20' })
      const response = await fetch('/api/v1/members?' + args, {
        credentials: 'same-origin', cache: 'no-store', signal: controller.signal,
      })
      if (controller.signal.aborted) return
      if (!response.ok) throw new Error('Member search unavailable')
      const page: Page = await response.json()
      if (controller.signal.aborted) return
      if (!Array.isArray(page.items)) throw new Error('Invalid search')
      setCandidates(page.items)
      setSelected(page.items[0]?.id ?? '')
    } catch {
      if (!controller.signal.aborted) setError(true)
    } finally {
      if (candidateRequest.current === controller) candidateRequest.current = null
    }
  }

  async function change(memberId: string, method: 'POST' | 'DELETE') {
    setBusy(true)
    setError(false)
    try {
      const response = await fetch(endpoint + '/' + encodeURIComponent(memberId), {
        method,
        credentials: 'same-origin',
        headers: { 'X-CSRF-Token': csrfToken },
      })
      if (response.status === 403) { setDenied(true); return }
      if (!response.ok) throw new Error('Assignment rejected')
      setMembers(null)
      setRevision(value => value + 1)
      onChanged()
    } catch {
      setError(true)
    } finally {
      setBusy(false)
    }
  }

  // Without a member-directory grant, no member names or IDs are shown.
  if (denied) return null
  return (
    <div>
      <h3>{t.roster}</h3>
      {error && <><p role="alert">{t.error}</p><button type="button" onClick={() => {
        setError(false); setMembers(null); void load()
      }}>{t.retry}</button></>}
      {members === null ? <p role="status">{t.loading}</p>
        : members.length === 0 ? <p>{t.empty}</p>
          : <ul>{members.map(member => <li key={member.id}>{member.firstName} {member.lastName}{' '}
            <button type="button" disabled={busy} onClick={() => { void change(member.id, 'DELETE') }}>
              {t.remove}
            </button>
          </li>)}</ul>}
      {cursor && <button type="button" disabled={busy} onClick={() => { void load(cursor) }}>{t.more}</button>}
      <form onSubmit={event => { void findMember(event) }}>
        <label>{t.search}<input value={search} maxLength={100} onChange={event => setSearch(event.target.value)} /></label>
        <button type="submit">{t.find}</button>
      </form>
      {candidates.length > 0 && <form onSubmit={event => {
        event.preventDefault()
        if (selected) void change(selected, 'POST')
      }}>
        <select aria-label={t.search} value={selected} onChange={event => setSelected(event.target.value)}>
          {candidates.map(member => <option key={member.id} value={member.id}>
            {member.firstName} {member.lastName}
          </option>)}
        </select>
        <button type="submit" disabled={!selected || busy}>{t.add}</button>
      </form>}
    </div>
  )
}
