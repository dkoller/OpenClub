import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import type { Language } from './language'
import { writeJson } from './api'
import { fetchMemberPage, memberCreateBody, type Member, type MemberDraft } from './members'
import { draftFromDetails, fetchMemberForEdit, reactivateMember, replaceMember, setMemberJsNumber, setMemberPublicListing } from './memberDetails'
import { MemberDetailsView } from './MemberDetailsView'
import { Reveal } from './Reveal'

const strings = {
  de: { newMember: 'Neues Mitglied', js: 'J+S-Personennummer', jsHint: 'Nur Ziffern; für die J+S-Anwesenheitskontrolle.', listed: 'In öffentlicher Mitgliederliste zeigen', changed: 'Nicht gespeichert: Jemand hat dieses Mitglied inzwischen geändert. Bitte erneut bearbeiten.', invalid: 'Bitte Angaben prüfen: Erwachsene brauchen E-Mail und Telefon, Spieler eventuell ein Geschlecht.', archivedNotice: 'Mitglied archiviert.', undo: 'Archivierung rückgängig machen', reactivated: 'Mitglied reaktiviert (ohne frühere Rechte, Teams oder Anmeldung).', stale: 'Reaktivierung nicht möglich: Der Eintrag erfüllt die aktuellen Vereinsregeln nicht mehr.', heading: 'Mitglieder verwalten', search: 'Name suchen', find: 'Suchen', loading: 'Mitglieder werden geladen…', denied: 'Keine Berechtigung zur Mitgliederverwaltung.', unavailable: 'Mitgliederverwaltung nicht verfügbar.', retry: 'Erneut versuchen', first: 'Vorname', last: 'Nachname', dob: 'Geburtsdatum', email: 'E-Mail', phone: 'Telefon', create: 'Mitglied hinzufügen', edit: 'Bearbeiten', save: 'Speichern', cancel: 'Abbrechen', archive: 'Archivieren', more: 'Weitere Mitglieder', saved: 'Mitglied aktualisiert.', empty: 'Keine Mitglieder gefunden.', problem: 'Aktion fehlgeschlagen.', player: 'Spieler', gender: 'Geschlecht (Freitext)', policy: 'Für Spieler in diesem Verein erforderlich.', adultContacts: 'Für Erwachsene sind E-Mail und Telefonnummer erforderlich; für Minderjährige sind beide freiwillig.', details: 'Details', confirmArchive: 'Mitglied wirklich archivieren?', confirm: 'Archivierung bestätigen', adultFrom: 'Erwachsen ab {age} Jahren.' },
  en: { newMember: 'New member', js: 'J+S person number', jsHint: 'Digits only; for the J+S attendance record.', listed: 'Show on public member list', changed: 'Not saved: someone changed this member in the meantime. Please edit it again.', invalid: 'Please check the fields: adults need email and phone, players may need a gender.', archivedNotice: 'Member archived.', undo: 'Undo archive', reactivated: 'Member reactivated (without former rights, teams or sign-in).', stale: 'Cannot reactivate: the record no longer meets the club’s current rules.', heading: 'Manage members', search: 'Search names', find: 'Search', loading: 'Loading members…', denied: 'No permission to manage members.', unavailable: 'Member management unavailable.', retry: 'Try again', first: 'First name', last: 'Last name', dob: 'Date of birth', email: 'Email', phone: 'Phone', create: 'Add member', edit: 'Edit', save: 'Save', cancel: 'Cancel', archive: 'Archive', more: 'More members', saved: 'Member updated.', empty: 'No members found.', problem: 'Action failed.', player: 'Player', gender: 'Gender (free text)', policy: 'Required for players in this club.', adultContacts: 'Adults must provide both email and phone; both are optional for minors.', details: 'Details', confirmArchive: 'Archive this member?', confirm: 'Confirm archive', adultFrom: 'Adult from age {age}.' },
  fr: { newMember: 'Nouveau membre', js: 'Numéro de personne J+S', jsHint: 'Chiffres uniquement ; pour le contrôle des présences J+S.', listed: 'Afficher dans la liste publique', changed: 'Non enregistré : quelqu’un a modifié ce membre entre-temps. Veuillez le modifier à nouveau.', invalid: 'Vérifiez les champs : les adultes ont besoin d’un e-mail et d’un téléphone, les joueurs parfois d’un genre.', archivedNotice: 'Membre archivé.', undo: 'Annuler l’archivage', reactivated: 'Membre réactivé (sans anciens droits, équipes ni connexion).', stale: 'Réactivation impossible : la fiche ne respecte plus les règles actuelles du club.', heading: 'Gérer les membres', search: 'Rechercher un nom', find: 'Rechercher', loading: 'Chargement des membres…', denied: 'Accès à la gestion des membres refusé.', unavailable: 'Gestion des membres indisponible.', retry: 'Réessayer', first: 'Prénom', last: 'Nom', dob: 'Date de naissance', email: 'E-mail', phone: 'Téléphone', create: 'Ajouter un membre', edit: 'Modifier', save: 'Enregistrer', cancel: 'Annuler', archive: 'Archiver', more: 'Plus de membres', saved: 'Membre mis à jour.', empty: 'Aucun membre trouvé.', problem: 'Action échouée.', player: 'Joueur', gender: 'Genre (texte libre)', policy: 'Obligatoire pour les joueurs de ce club.', adultContacts: 'Pour les adultes, l’e-mail et le téléphone sont obligatoires ; les deux sont facultatifs pour les mineurs.', details: 'Détails', confirmArchive: 'Archiver ce membre ?', confirm: 'Confirmer l’archivage', adultFrom: 'Adulte dès {age} ans.' },
  it: { newMember: 'Nuovo membro', js: 'Numero personale G+S', jsHint: 'Solo cifre; per il controllo delle presenze G+S.', listed: 'Mostra nell’elenco pubblico', changed: 'Non salvato: qualcuno ha modificato questo membro nel frattempo. Modificalo di nuovo.', invalid: 'Controlla i campi: gli adulti richiedono email e telefono, i giocatori talvolta un genere.', archivedNotice: 'Membro archiviato.', undo: 'Annulla archiviazione', reactivated: 'Membro riattivato (senza diritti, squadre o accesso precedenti).', stale: 'Riattivazione impossibile: la scheda non rispetta più le regole attuali del club.', heading: 'Gestisci membri', search: 'Cerca nomi', find: 'Cerca', loading: 'Caricamento membri…', denied: 'Nessun permesso di gestione membri.', unavailable: 'Gestione membri non disponibile.', retry: 'Riprova', first: 'Nome', last: 'Cognome', dob: 'Data di nascita', email: 'Email', phone: 'Telefono', create: 'Aggiungi membro', edit: 'Modifica', save: 'Salva', cancel: 'Annulla', archive: 'Archivia', more: 'Altri membri', saved: 'Membro aggiornato.', empty: 'Nessun membro trovato.', problem: 'Operazione non riuscita.', player: 'Giocatore', gender: 'Genere (testo libero)', policy: 'Obbligatorio per i giocatori di questo club.', adultContacts: 'Per gli adulti sono obbligatori sia l’email sia il telefono; per i minorenni sono facoltativi.', details: 'Dettagli', confirmArchive: 'Archiviare questo membro?', confirm: 'Conferma archiviazione', adultFrom: 'Adulto da {age} anni.' },
} satisfies Record<Language, Record<string, string>>

export function MemberAdmin({ language, csrfToken, onChanged, genderRequiredForPlayers, adultAgeYears }: {
  language: Language; csrfToken: string; onChanged: () => void; genderRequiredForPlayers: boolean
  adultAgeYears: number
}) {
  const t = strings[language]
  const [items, setItems] = useState<Member[] | null>(null)
  const [cursor, setCursor] = useState<string | undefined>()
  const [failedPage, setFailedPage] = useState<{ q: string; cursor?: string } | null>(null)
  const [loadingPage, setLoadingPage] = useState(false)
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const [denied, setDenied] = useState(false)
  const [failure, setFailure] = useState<'unavailable' | 'problem' | null>(null)
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState<{ id: string; draft: MemberDraft; version: string; listed: boolean; wasListed: boolean; js: string; wasJs: string } | null>(null)
  const [lastArchived, setLastArchived] = useState<Member | null>(null)
  const [notice, setNotice] = useState<'invalid' | 'archivedNotice' | 'reactivated' | 'stale' | 'changed' | null>(null)
  const [confirmingArchive, setConfirmingArchive] = useState<string | null>(null)
  const [showing, setShowing] = useState<string | null>(null)
  const [first, setFirst] = useState('')
  const [last, setLast] = useState('')
  const [dob, setDob] = useState('')
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [isPlayer, setIsPlayer] = useState(false)
  const [gender, setGender] = useState('')
  const activeLoad = useRef<AbortController | null>(null)

  const load = useCallback(async (q: string, next?: string) => {
    activeLoad.current?.abort()
    const controller = new AbortController()
    activeLoad.current = controller
    setLoadingPage(true)
    setFailedPage(null)
    try {
      const page = await fetchMemberPage(q, next, controller.signal)
      if (controller.signal.aborted) return
      if (page === 'denied') { setDenied(true); return }
      setItems(previous => next ? [...(previous ?? []), ...page.items] : page.items)
      setCursor(page.nextCursor)
      setFailedPage(null)
      setFailure(null)
    } catch {
      if (!controller.signal.aborted) {
        setFailedPage({ q, cursor: next })
        setFailure('unavailable')
      }
    } finally {
      if (activeLoad.current === controller) {
        activeLoad.current = null
        setLoadingPage(false)
      }
    }
  }, [])

  useEffect(() => {
    void load('')
    return () => activeLoad.current?.abort()
  }, []) // Refresh after each write, search or language change below.

  async function mutation(path: string, method: 'POST' | 'PATCH', body?: object): Promise<boolean> {
    setBusy(true)
    setFailure(null)
    setNotice(null)
    try {
      if (await writeJson(path, method, csrfToken, body) === 'denied') {
        setDenied(true)
        throw new Error('Request rejected')
      }
      setEditing(null)
      setConfirmingArchive(null)
      await load(query)
      onChanged()
      setFailure(null)
      if (method === 'POST' && path === '/api/v1/members') {
        setFirst(''); setLast(''); setDob(''); setEmail(''); setPhone(''); setIsPlayer(false); setGender('')
      }
      return true
    } catch {
      setFailure('problem')
      return false
    } finally {
      setBusy(false)
    }
  }

  /** The form starts from the editor's full read, so a save never clears a field the editor did not see. */
  async function startEdit(member: Member) {
    setBusy(true)
    setFailure(null)
    setNotice(null)
    try {
      const read = await fetchMemberForEdit(member.id)
      if (read === 'denied') { setDenied(true); return }
      if (read === 'not-found') { setFailure('problem'); return }
      const listed = read.details.listedPublicly ?? true
      const js = read.details.jsPersonNumber ?? ''
      setEditing({ id: member.id, draft: draftFromDetails(read.details), version: read.version, listed, wasListed: listed, js, wasJs: js })
    } catch {
      setFailure('problem')
    } finally {
      setBusy(false)
    }
  }

  async function saveEdit(id: string, draft: MemberDraft, version: string, listed: boolean, wasListed: boolean, js: string, wasJs: string) {
    setBusy(true)
    setFailure(null)
    setNotice(null)
    try {
      // OpenAPI 1.15.0: the public-list switch is its own conditional change; its new version then guards the rest.
      let current = version
      if (listed !== wasListed) {
        const switched = await setMemberPublicListing(id, listed, version, csrfToken)
        if (switched.outcome === 'denied') { setDenied(true); return }
        if (switched.outcome === 'changed') { setEditing(null); setNotice('changed'); return }
        if (switched.outcome !== 'ok') { setFailure('problem'); return }
        current = switched.version
        setEditing(editing => editing && { ...editing, version: current, wasListed: listed })
      }
      // OpenAPI 1.27.0: the J+S number is its own conditional change too.
      if (js.trim() !== wasJs) {
        const changed = await setMemberJsNumber(id, js.trim() || null, current, csrfToken)
        if (changed.outcome === 'denied') { setDenied(true); return }
        if (changed.outcome === 'changed') { setEditing(null); setNotice('changed'); return }
        if (changed.outcome === 'invalid') { setNotice('invalid'); return }
        if (changed.outcome !== 'ok') { setFailure('problem'); return }
        current = changed.version
        setEditing(editing => editing && { ...editing, version: current, wasJs: js.trim() })
      }
      const outcome = await replaceMember(id, memberCreateBody(draft), current, csrfToken)
      if (outcome === 'denied') { setDenied(true); return }
      if (outcome === 'invalid') { setNotice('invalid'); return }
      // Someone else changed the member since the form was filled: nothing was saved; Edit shows the new values.
      if (outcome === 'changed') { setEditing(null); setNotice('changed'); return }
      if (outcome !== 'ok') { setFailure('problem'); return }
      setEditing(null)
      await load(query)
      onChanged()
    } catch {
      setFailure('problem')
    } finally {
      setBusy(false)
    }
  }

  async function undoArchive(member: Member) {
    setBusy(true)
    setFailure(null)
    try {
      const outcome = await reactivateMember(member.id, csrfToken)
      if (outcome === 'denied') { setDenied(true); return }
      setLastArchived(null)
      if (outcome === 'conflict') { setNotice('stale'); return }
      if (outcome !== 'ok') { setFailure('problem'); return }
      setNotice('reactivated')
      await load(query)
      onChanged()
    } catch {
      setFailure('problem')
    } finally {
      setBusy(false)
    }
  }

  function submitSearch(event: FormEvent) {
    event.preventDefault()
    setQuery(search)
    setConfirmingArchive(null)
    setItems(null)
    void load(search)
  }

  if (denied) return <section aria-label={t.heading}><h2>{t.heading}</h2><p role="alert">{t.denied}</p></section>

  return (
    <section aria-labelledby="member-admin-heading">
      <h2 id="member-admin-heading">{t.heading}</h2>
      <form className="search" onSubmit={submitSearch}>
        <label htmlFor="member-search">{t.search}</label>
        <input id="member-search" value={search} maxLength={100} onChange={e => setSearch(e.target.value)} />
        <button type="submit">{t.find}</button>
      </form>
      {failure && <p role="alert">{t[failure]} <button type="button" onClick={() => { void load(failedPage?.q ?? query, failedPage?.cursor) }} disabled={loadingPage}>{t.retry}</button></p>}
      {notice && <p role="status">{t[notice]}{notice === 'archivedNotice' && lastArchived && <>{' '}
        <button type="button" disabled={busy} onClick={() => { void undoArchive(lastArchived) }}>{t.undo}</button></>}</p>}
      {items === null ? (failure ? null : <p>{t.loading}</p>) : items.length === 0 ? <p>{t.empty}</p> : (
        <ul className="rows card member-rows">
          {items.map(member => (
            <li key={member.id} className={showing === member.id || editing?.id === member.id ? 'pad wide' : 'pad'}>
              {editing?.id === member.id ? (
                <form onSubmit={event => {
                  event.preventDefault()
                  void saveEdit(member.id, editing.draft, editing.version, editing.listed, editing.wasListed, editing.js, editing.wasJs)
                }}>
                  <label>{t.first}<input value={editing.draft.firstName} required maxLength={200} onChange={e => setEditing({ ...editing, draft: { ...editing.draft, firstName: e.target.value } })} /></label>
                  <label>{t.last}<input value={editing.draft.lastName} required maxLength={200} onChange={e => setEditing({ ...editing, draft: { ...editing.draft, lastName: e.target.value } })} /></label>
                  <label>{t.dob}<input type="date" value={editing.draft.dateOfBirth} required onChange={e => setEditing({ ...editing, draft: { ...editing.draft, dateOfBirth: e.target.value } })} /></label>
                  <label>{t.email}<input type="email" value={editing.draft.email} maxLength={320} onChange={e => setEditing({ ...editing, draft: { ...editing.draft, email: e.target.value } })} /></label>
                  <label>{t.phone}<input type="tel" value={editing.draft.phone} maxLength={40} onChange={e => setEditing({ ...editing, draft: { ...editing.draft, phone: e.target.value } })} /></label>
                  <label>{t.player}<input type="checkbox" checked={editing.draft.isPlayer} onChange={e => setEditing({ ...editing, draft: { ...editing.draft, isPlayer: e.target.checked, gender: e.target.checked ? editing.draft.gender : '' } })} /></label>
                  {editing.draft.isPlayer && <label>{t.gender}<input value={editing.draft.gender} maxLength={200} required={genderRequiredForPlayers} onChange={e => setEditing({ ...editing, draft: { ...editing.draft, gender: e.target.value } })} /></label>}
                  <label>{t.listed}<input type="checkbox" checked={editing.listed} onChange={e => setEditing({ ...editing, listed: e.target.checked })} /></label>
                  <label>{t.js}<input value={editing.js} inputMode="numeric" pattern="[0-9]{1,12}" maxLength={12} aria-describedby={'js-hint-' + member.id}
                    onChange={e => setEditing({ ...editing, js: e.target.value })} /><span id={'js-hint-' + member.id} className="muted small">{t.jsHint}</span></label>
                  <button disabled={busy} type="submit">{t.save}</button>
                  <button type="button" onClick={() => setEditing(null)}>{t.cancel}</button>
                </form>
              ) : (
                <>
                  {/* The name opens the member: details first, then Edit and Archive (design rule, 2026-11-26). */}
                  <button type="button" className="row-toggle" aria-expanded={showing === member.id}
                    onClick={() => { setShowing(showing === member.id ? null : member.id); setConfirmingArchive(null) }}>
                    <span className="row-name">{member.firstName} {member.lastName}
                      {member.teams && member.teams.length > 0 && <span className="muted small row-teams">{member.teams.join(', ')}</span>}
                    </span></button>
                  {showing === member.id && <>
                  <MemberDetailsView memberId={member.id} language={language} onClose={() => setShowing(null)} />
                  <div className="actions">
                  <button type="button" disabled={busy} onClick={() => { void startEdit(member) }}>{t.edit}</button>
                  {confirmingArchive === member.id ? (
                    <span role="group" aria-label={`${t.confirmArchive} ${member.firstName} ${member.lastName}`}>
                      <span>{t.confirmArchive}</span>{' '}
                      <button type="button" disabled={busy} onClick={() => {
                        void mutation('/api/v1/members/' + member.id + '/archive', 'POST').then(done => {
                          if (done) { setLastArchived(member); setNotice('archivedNotice') }
                        })
                      }}>{t.confirm}</button>{' '}
                      <button type="button" disabled={busy} onClick={() => setConfirmingArchive(null)}>
                        {t.cancel}
                      </button>
                    </span>
                  ) : (
                    <button type="button" disabled={busy}
                      onClick={() => setConfirmingArchive(member.id)}>{t.archive}</button>
                  )}
                  </div>
                  </>}
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {cursor && <button type="button" disabled={busy || loadingPage || !!failure} onClick={() => { void load(query, cursor) }}>{t.more}</button>}
      <Reveal label={t.newMember}><form className="card pad" onSubmit={event => {
        event.preventDefault()
        void mutation('/api/v1/members', 'POST', memberCreateBody({
          firstName: first, lastName: last, dateOfBirth: dob, email, phone, isPlayer, gender,
        }))
      }}>
        <label>{t.first}<input value={first} required maxLength={200} onChange={e => setFirst(e.target.value)} /></label>
        <label>{t.last}<input value={last} required maxLength={200} onChange={e => setLast(e.target.value)} /></label>
        <label>{t.dob}<input type="date" value={dob} required onChange={e => setDob(e.target.value)} /></label>
        <p id="member-adult-contacts-help">{t.adultContacts} {t.adultFrom.replace('{age}', String(adultAgeYears))}</p>
        <label>{t.email}<input type="email" value={email} aria-describedby="member-adult-contacts-help"
          onChange={e => setEmail(e.target.value)} /></label>
        <label>{t.phone}<input type="tel" value={phone} aria-describedby="member-adult-contacts-help"
          onChange={e => setPhone(e.target.value)} /></label>
        <label>{t.player}<input type="checkbox" checked={isPlayer}
          onChange={e => { setIsPlayer(e.target.checked); if (!e.target.checked) setGender('') }} /></label>
        {isPlayer && <label>{t.gender}
          <input value={gender} maxLength={200} required={genderRequiredForPlayers}
            onChange={e => setGender(e.target.value)} />
          {genderRequiredForPlayers && <span>{t.policy}</span>}
        </label>}
        <button type="submit" disabled={busy}>{t.create}</button>
      </form></Reveal>
    </section>
  )
}
