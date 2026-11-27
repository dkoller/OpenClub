import { useEffect, useState, type FormEvent } from 'react'
import { formatDate } from './dates'
import type { Language } from './language'
import type { Member } from './members'
import { listDependents, readDependent, replaceDependentContacts, type Dependent } from './guardians'

const words = {
  de: { title: 'Meine Kinder', open: 'Öffnen', close: 'Schliessen', dob: 'Geburtsdatum', player: 'Spieler', yes: 'Ja', no: 'Nein', teams: 'Teams', noTeams: 'keine', email: 'E-Mail', phone: 'Telefon', save: 'Kontaktdaten speichern', saved: 'Kontaktdaten gespeichert.', invalid: 'Bitte E-Mail und Telefon prüfen.', gone: 'Kein Zugriff mehr auf dieses Kind.', error: 'Nicht verfügbar.', none: 'Keine Kinder eingetragen. Das trägt die Vereinsverwaltung ein.' },
  en: { title: 'My children', open: 'Open', close: 'Close', dob: 'Date of birth', player: 'Player', yes: 'Yes', no: 'No', teams: 'Teams', noTeams: 'none', email: 'Email', phone: 'Phone', save: 'Save contacts', saved: 'Contacts saved.', invalid: 'Please check the email and phone.', gone: 'You no longer have access to this child.', error: 'Unavailable.', none: 'No children recorded. The club administration records them.' },
  fr: { title: 'Mes enfants', open: 'Ouvrir', close: 'Fermer', dob: 'Date de naissance', player: 'Joueur', yes: 'Oui', no: 'Non', teams: 'Équipes', noTeams: 'aucune', email: 'E-mail', phone: 'Téléphone', save: 'Enregistrer les coordonnées', saved: 'Coordonnées enregistrées.', invalid: 'Vérifiez l’e-mail et le téléphone.', gone: 'Vous n’avez plus accès à cet enfant.', error: 'Indisponible.', none: 'Aucun enfant enregistré. L’administration du club les enregistre.' },
  it: { title: 'I miei figli', open: 'Apri', close: 'Chiudi', dob: 'Data di nascita', player: 'Giocatore', yes: 'Sì', no: 'No', teams: 'Squadre', noTeams: 'nessuna', email: 'Email', phone: 'Telefono', save: 'Salva contatti', saved: 'Contatti salvati.', invalid: 'Controlla email e telefono.', gone: 'Non hai più accesso a questo bambino.', error: 'Non disponibile.', none: 'Nessun figlio registrato. Li registra l’amministrazione del club.' },
} satisfies Record<Language, Record<string, string>>

/**
 * A guardian's own minors (OpenAPI 1.6.0), on its own page #/family/children; the Family overview links here only
 * for guardians. The form starts from the guardian's read, so saving never clears a contact the guardian did not see.
 */
export function MyDependents({ language, csrfToken }: { language: Language; csrfToken: string }) {
  const t = words[language]
  const [dependents, setDependents] = useState<Member[] | null>(null)
  const [open, setOpen] = useState<Dependent | null>(null)
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [notice, setNotice] = useState<'saved' | 'invalid' | 'gone' | 'error' | null>(null)
  const [busy, setBusy] = useState(false)

  async function refresh() {
    try {
      const result = await listDependents()
      setDependents(result === 'denied' ? [] : result)
    } catch { setDependents([]) }
  }
  useEffect(() => { void refresh() }, [])

  async function show(id: string) {
    setNotice(null)
    try {
      const result = await readDependent(id)
      if (result === 'denied') { setOpen(null); setNotice('gone'); void refresh(); return }
      setOpen(result)
      setEmail(result.email ?? '')
      setPhone(result.phone ?? '')
    } catch { setNotice('error') }
  }

  async function save(event: FormEvent) {
    event.preventDefault()
    if (!open) return
    setBusy(true)
    setNotice(null)
    try {
      const outcome = await replaceDependentContacts(open.id, email, phone, csrfToken)
      if (outcome === 'ok') { setNotice('saved'); await show(open.id); setNotice('saved') }
      else if (outcome === 'invalid') setNotice('invalid')
      else { setOpen(null); setNotice('gone'); void refresh() }
    } catch { setNotice('error') } finally { setBusy(false) }
  }

  if (dependents === null) return null
  if (dependents.length === 0 && !notice) return <section aria-label={t.title}><h1>{t.title}</h1><p className="muted">{t.none}</p></section>
  return (
    <section aria-label={t.title}>
      <h1>{t.title}</h1>
      {/* One row per child, like the Family menu: tapping the name opens their details and contacts. */}
      <ul className="rows card member-rows">{dependents.map(child => <li key={child.id} className={open?.id === child.id ? 'pad wide' : 'pad'}>
        <button type="button" className="row-toggle" aria-expanded={open?.id === child.id}
          onClick={() => { if (open?.id === child.id) setOpen(null); else void show(child.id) }}>
          {child.firstName} {child.lastName}
        </button>
        {open?.id === child.id && <div className="stack">
          <dl className="summary">
            <div><dt>{t.dob}</dt><dd>{formatDate(open.dateOfBirth)}</dd></div>
            <div><dt>{t.player}</dt><dd>{open.isPlayer ? t.yes : t.no}</dd></div>
            <div><dt>{t.teams}</dt><dd>{open.teams.length ? open.teams.join(', ') : t.noTeams}</dd></div>
          </dl>
          <form className="stack" onSubmit={event => { void save(event) }}>
            <label>{t.email}<input type="email" value={email} maxLength={320} onChange={event => setEmail(event.target.value)} /></label>
            <label>{t.phone}<input type="tel" value={phone} maxLength={40} onChange={event => setPhone(event.target.value)} /></label>
            <button type="submit" disabled={busy}>{t.save}</button>
          </form>
        </div>}
      </li>)}</ul>
      {notice && <p role={notice === 'error' ? 'alert' : 'status'}>{t[notice]}</p>}
    </section>
  )
}
