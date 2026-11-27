import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import type { Language } from './language'
import { fetchMemberPage, type Member } from './members'
import { changeAssignment, readRoster } from './teams'
import { MemberDetailsView } from './MemberDetailsView'

const words = {
  de: { addTitle: 'Mitglied hinzufügen', roster: 'Teammitglieder', search: 'Mitglied suchen', find: 'Suchen', add: 'Zuweisen', remove: 'Aus dem Team entfernen', contacts: 'Kontaktdaten', empty: 'Keine Zuweisungen.', more: 'Weitere Mitglieder', error: 'Teamzuweisung fehlgeschlagen.', retry: 'Erneut versuchen', loading: 'Teammitglieder werden geladen…', denied: 'Die Mitglieder dieses Teams sind für dich nicht sichtbar.' },
  en: { addTitle: 'Add a member', roster: 'Team members', search: 'Find member', find: 'Search', add: 'Assign', remove: 'Remove from team', contacts: 'Contact details', empty: 'No assignments.', more: 'More members', error: 'Team assignment failed.', retry: 'Try again', loading: 'Loading team members…', denied: 'You cannot see the members of this team.' },
  fr: { addTitle: 'Ajouter un membre', roster: 'Membres de l’équipe', search: 'Chercher un membre', find: 'Chercher', add: 'Affecter', remove: 'Retirer de l’équipe', contacts: 'Coordonnées', empty: 'Aucune affectation.', more: 'Plus de membres', error: 'Affectation impossible.', retry: 'Réessayer', loading: 'Chargement des membres…', denied: 'Vous ne voyez pas les membres de cette équipe.' },
  it: { addTitle: 'Aggiungi un membro', roster: 'Membri della squadra', search: 'Cerca membro', find: 'Cerca', add: 'Assegna', remove: 'Rimuovi dalla squadra', contacts: 'Contatti', empty: 'Nessuna assegnazione.', more: 'Altri membri', error: 'Assegnazione non riuscita.', retry: 'Riprova', loading: 'Caricamento membri…', denied: 'Non puoi vedere i membri di questa squadra.' },
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
  const [showing, setShowing] = useState<string | null>(null)
  const [revision, setRevision] = useState(0)
  const candidateRequest = useRef<AbortController | null>(null)
  const rosterRequest = useRef<AbortController | null>(null)

  const load = useCallback(async (next?: string) => {
    rosterRequest.current?.abort()
    const controller = new AbortController()
    rosterRequest.current = controller
    try {
      const page = await readRoster(teamId, next, controller.signal)
      if (controller.signal.aborted) return
      if (page === 'denied') { setDenied(true); return }
      setMembers(previous => next ? [...(previous ?? []), ...page.items] : page.items)
      setCursor(page.nextCursor)
      setError(false)
    } catch {
      if (!controller.signal.aborted) {
        setError(true)
        setMembers(previous => previous ?? [])
      }
    } finally {
      if (rosterRequest.current === controller) rosterRequest.current = null
    }
  }, [teamId])

  useEffect(() => {
    void load()
    return () => rosterRequest.current?.abort()
  }, [load, revision])
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
      const page = await fetchMemberPage(search, undefined, controller.signal)
      if (controller.signal.aborted) return
      if (page === 'denied') { setDenied(true); return }
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
      if (await changeAssignment(teamId, memberId, method, csrfToken) === 'denied') { setDenied(true); return }
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
  if (denied) return <p className="muted">{t.denied}</p>
  return (
    <section aria-label={t.roster} className="stack">
      {error && <><p role="alert">{t.error}</p><button type="button" onClick={() => {
        setError(false); setMembers(null); void load()
      }}>{t.retry}</button></>}
      {members === null ? <p role="status">{t.loading}</p>
        : members.length === 0 ? <p>{t.empty}</p>
          // Tapping a name opens the contacts; removing from the team sits inside, as a quiet link.
          : <ul className="rows card member-rows">{members.map(member => <li key={member.id} className={showing === member.id ? 'pad wide' : 'pad'}>
            <button type="button" className="row-toggle" aria-expanded={showing === member.id}
              onClick={() => setShowing(showing === member.id ? null : member.id)}>{member.firstName} {member.lastName}</button>
            {showing === member.id && <div className="stack">
              <MemberDetailsView memberId={member.id} language={language} onClose={() => setShowing(null)} />
              <button type="button" className="danger-quiet" disabled={busy} onClick={() => { void change(member.id, 'DELETE') }}>
                {t.remove}
              </button>
            </div>}
          </li>)}</ul>}
      {cursor && <button type="button" disabled={busy} onClick={() => { void load(cursor) }}>{t.more}</button>}
      <h2>{t.addTitle}</h2>
      <form className="search" onSubmit={event => { void findMember(event) }}>
        <label htmlFor="team-member-search">{t.search}</label>
        <input id="team-member-search" value={search} maxLength={100} onChange={event => setSearch(event.target.value)} />
        <button type="submit">{t.find}</button>
      </form>
      {candidates.length > 0 && <form className="search" onSubmit={event => {
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
    </section>
  )
}
