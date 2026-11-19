import { useEffect, useRef, useState, type FormEvent } from 'react'
import type { Language } from './language'
import { fetchMemberPage, type Member } from './members'
import { latestLookup } from './roles'
import { addGuardian, listGuardians, removeGuardian } from './guardians'

const words = {
  de: { title: 'Erziehungsberechtigte', minorSearch: 'Minderjähriges Mitglied suchen', guardianSearch: 'Erziehungsberechtigte Person suchen', find: 'Suchen', minor: 'Minderjähriges Mitglied', guardian: 'Erziehungsberechtigte Person', add: 'Als erziehungsberechtigt eintragen', remove: 'Entfernen', none: 'Keine Erziehungsberechtigten eingetragen.', notMinor: 'Dieses Mitglied ist nicht minderjährig.', conflict: 'Nicht möglich: Nur aktive Erwachsene mit Anmeldung, höchstens zwei pro Kind.', attest: 'Sie bestätigen, dass Sie die Beziehung ausserhalb von OpenClub geprüft haben.', added: 'Eingetragen.', removed: 'Entfernt.', error: 'Erziehungsberechtigte nicht verfügbar.', empty: 'Keine Mitglieder gefunden.' },
  en: { title: 'Guardians', minorSearch: 'Find a minor', guardianSearch: 'Find a guardian', find: 'Search', minor: 'Minor', guardian: 'Guardian', add: 'Add as guardian', remove: 'Remove', none: 'No guardians recorded.', notMinor: 'This member is not a minor.', conflict: 'Not possible: only active adults with a sign-in, at most two per child.', attest: 'You confirm that you checked the relationship outside OpenClub.', added: 'Guardian added.', removed: 'Guardian removed.', error: 'Guardians unavailable.', empty: 'No members found.' },
  fr: { title: 'Représentants légaux', minorSearch: 'Chercher un membre mineur', guardianSearch: 'Chercher un représentant légal', find: 'Chercher', minor: 'Membre mineur', guardian: 'Représentant légal', add: 'Ajouter comme représentant légal', remove: 'Retirer', none: 'Aucun représentant légal enregistré.', notMinor: 'Ce membre n’est pas mineur.', conflict: 'Impossible : seulement des adultes actifs avec connexion, au plus deux par enfant.', attest: 'Vous confirmez avoir vérifié la relation en dehors d’OpenClub.', added: 'Représentant légal ajouté.', removed: 'Représentant légal retiré.', error: 'Représentants légaux indisponibles.', empty: 'Aucun membre trouvé.' },
  it: { title: 'Tutori legali', minorSearch: 'Cerca un membro minorenne', guardianSearch: 'Cerca un tutore', find: 'Cerca', minor: 'Membro minorenne', guardian: 'Tutore', add: 'Aggiungi come tutore', remove: 'Rimuovi', none: 'Nessun tutore registrato.', notMinor: 'Questo membro non è minorenne.', conflict: 'Non possibile: solo adulti attivi con accesso, al massimo due per bambino.', attest: 'Confermi di aver verificato il rapporto al di fuori di OpenClub.', added: 'Tutore aggiunto.', removed: 'Tutore rimosso.', error: 'Tutori non disponibili.', empty: 'Nessun membro trovato.' },
} satisfies Record<Language, Record<string, string>>

type Notice = 'notMinor' | 'conflict' | 'added' | 'removed' | 'error' | null

/** Editors record and remove a minor's guardians (OpenAPI 1.6.0). The server enforces every rule. */
export function GuardianAdmin({ language, csrfToken }: { language: Language; csrfToken: string }) {
  const t = words[language]
  const [minorQuery, setMinorQuery] = useState('')
  const [minors, setMinors] = useState<Member[] | null>(null)
  const [minorId, setMinorId] = useState('')
  const [guardians, setGuardians] = useState<Member[] | null>(null)
  const [guardianQuery, setGuardianQuery] = useState('')
  const [candidates, setCandidates] = useState<Member[]>([])
  const [candidateId, setCandidateId] = useState('')
  const [notice, setNotice] = useState<Notice>(null)
  const [denied, setDenied] = useState(false)
  const [busy, setBusy] = useState(false)
  const lookups = useRef(latestLookup())
  useEffect(() => () => lookups.current.cancel(), [])

  async function show(id: string) {
    setMinorId(id)
    setGuardians(null)
    if (!id) return
    try {
      const result = await listGuardians(id)
      if (result === 'denied') { setDenied(true); return }
      if (result === 'not-found') { setNotice('notMinor'); setGuardians([]); return }
      setGuardians(result)
    } catch { setNotice('error') }
  }

  async function search(event: FormEvent, query: string, done: (items: Member[]) => void) {
    event.preventDefault()
    const signal = lookups.current.next()
    setNotice(null)
    try {
      const page = await fetchMemberPage(query, undefined, signal)
      if (signal.aborted) return
      if (page === 'denied') { setDenied(true); return }
      done(page.items)
    } catch { if (!signal.aborted) setNotice('error') }
  }

  async function change(run: () => Promise<'ok' | 'denied' | 'invalid' | 'not-found' | 'conflict'>, done: 'added' | 'removed') {
    setBusy(true)
    setNotice(null)
    try {
      const outcome = await run()
      if (outcome === 'denied') { setDenied(true); return }
      if (outcome === 'conflict' || outcome === 'invalid') { setNotice('conflict'); return }
      if (outcome === 'not-found') { setNotice('notMinor'); return }
      setNotice(done)
      await show(minorId)
    } catch { setNotice('error') } finally { setBusy(false) }
  }

  if (denied) return null
  return (
    <section aria-label={t.title}>
      <h2>{t.title}</h2>
      <form onSubmit={event => { void search(event, minorQuery, items => { setMinors(items); void show(items[0]?.id ?? '') }) }}>
        <label>{t.minorSearch}<input value={minorQuery} maxLength={100} onChange={event => setMinorQuery(event.target.value)} /></label>
        <button type="submit" disabled={busy}>{t.find}</button>
      </form>
      {minors?.length === 0 && <p>{t.empty}</p>}
      {minors && minors.length > 0 && <label>{t.minor}
        <select value={minorId} disabled={busy} onChange={event => { setNotice(null); void show(event.target.value) }}>
          {minors.map(member => <option key={member.id} value={member.id}>{member.firstName} {member.lastName}</option>)}
        </select>
      </label>}
      {guardians && notice !== 'notMinor' && <>
        {guardians.length === 0 ? <p>{t.none}</p> : <ul>{guardians.map(guardian => <li key={guardian.id}>
          {guardian.firstName} {guardian.lastName}{' '}
          <button type="button" disabled={busy}
            onClick={() => { void change(() => removeGuardian(minorId, guardian.id, csrfToken), 'removed') }}>{t.remove}</button>
        </li>)}</ul>}
        <form onSubmit={event => { void search(event, guardianQuery, items => { setCandidates(items); setCandidateId(items[0]?.id ?? '') }) }}>
          <label>{t.guardianSearch}<input value={guardianQuery} maxLength={100} onChange={event => setGuardianQuery(event.target.value)} /></label>
          <button type="submit" disabled={busy}>{t.find}</button>
        </form>
        {candidates.length > 0 && <div>
          <label>{t.guardian}
            <select value={candidateId} disabled={busy} onChange={event => setCandidateId(event.target.value)}>
              {candidates.map(member => <option key={member.id} value={member.id}>{member.firstName} {member.lastName}</option>)}
            </select>
          </label>
          <p id="guardian-attestation">{t.attest}</p>
          <button type="button" aria-describedby="guardian-attestation" disabled={busy || !candidateId}
            onClick={() => { void change(() => addGuardian(minorId, candidateId, csrfToken), 'added') }}>{t.add}</button>
        </div>}
      </>}
      {notice && <p role={notice === 'error' ? 'alert' : 'status'}>{t[notice]}</p>}
    </section>
  )
}
