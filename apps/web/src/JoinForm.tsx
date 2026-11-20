import { useEffect, useState, type FormEvent } from 'react'
import type { Language } from './language'
import { readJoinForm, submitJoinRequest, type JoinForm as Form } from './joinRequests'

const words = {
  de: { open: 'Mitglied werden', title: 'Aufnahmegesuch', self: 'Für mich', child: 'Für mein Kind', firstName: 'Vorname', lastName: 'Nachname', childFirstName: 'Vorname des Kindes', childLastName: 'Nachname des Kindes', birth: 'Geburtsdatum', parentFirstName: 'Ihr Vorname', parentLastName: 'Ihr Nachname', email: 'E-Mail', phone: 'Telefon', team: 'Gewünschtes Team (optional)', none: 'Kein bestimmtes', message: 'Nachricht (optional)', privacy: 'Ich habe die Datenschutzerklärung des Vereins gelesen.', send: 'Gesuch senden', sent: 'Danke! Der Verein meldet sich bei Ihnen.', invalid: 'Bitte Angaben prüfen (Erwachsene für sich, Eltern für ein Kind).', closed: 'Der Verein nimmt zurzeit keine Gesuche an.', full: 'Zurzeit sind zu viele Gesuche offen. Bitte später erneut versuchen.', limited: 'Zu viele Gesuche. Bitte in einer Stunde erneut versuchen.', failed: 'Nicht gesendet. Bitte erneut versuchen.', website: 'Nicht ausfüllen' },
  en: { open: 'Join the club', title: 'Membership application', self: 'For me', child: 'For my child', firstName: 'First name', lastName: 'Last name', childFirstName: 'Child’s first name', childLastName: 'Child’s last name', birth: 'Date of birth', parentFirstName: 'Your first name', parentLastName: 'Your last name', email: 'Email', phone: 'Phone', team: 'Preferred team (optional)', none: 'No preference', message: 'Message (optional)', privacy: 'I have read the club’s privacy notice.', send: 'Send application', sent: 'Thank you! The club will get in touch.', invalid: 'Please check the fields (adults apply for themselves, parents for a child).', closed: 'The club is not taking applications right now.', full: 'Too many applications are open right now. Please try again later.', limited: 'Too many applications. Please try again in an hour.', failed: 'Not sent. Please try again.', website: 'Leave empty' },
  fr: { open: 'Devenir membre', title: 'Demande d’adhésion', self: 'Pour moi', child: 'Pour mon enfant', firstName: 'Prénom', lastName: 'Nom', childFirstName: 'Prénom de l’enfant', childLastName: 'Nom de l’enfant', birth: 'Date de naissance', parentFirstName: 'Votre prénom', parentLastName: 'Votre nom', email: 'E-mail', phone: 'Téléphone', team: 'Équipe souhaitée (facultatif)', none: 'Pas de préférence', message: 'Message (facultatif)', privacy: 'J’ai lu la déclaration de protection des données du club.', send: 'Envoyer la demande', sent: 'Merci ! Le club vous contactera.', invalid: 'Vérifiez les champs (adultes pour eux-mêmes, parents pour un enfant).', closed: 'Le club n’accepte pas de demandes en ce moment.', full: 'Trop de demandes sont ouvertes. Réessayez plus tard.', limited: 'Trop de demandes. Réessayez dans une heure.', failed: 'Non envoyé. Veuillez réessayer.', website: 'Ne pas remplir' },
  it: { open: 'Diventa socio', title: 'Domanda di adesione', self: 'Per me', child: 'Per mio figlio/mia figlia', firstName: 'Nome', lastName: 'Cognome', childFirstName: 'Nome del bambino', childLastName: 'Cognome del bambino', birth: 'Data di nascita', parentFirstName: 'Il tuo nome', parentLastName: 'Il tuo cognome', email: 'Email', phone: 'Telefono', team: 'Squadra desiderata (facoltativo)', none: 'Nessuna preferenza', message: 'Messaggio (facoltativo)', privacy: 'Ho letto l’informativa sulla privacy del club.', send: 'Invia domanda', sent: 'Grazie! Il club ti contatterà.', invalid: 'Controlla i campi (adulti per sé, genitori per un figlio).', closed: 'Il club al momento non accetta domande.', full: 'Troppe domande aperte. Riprova più tardi.', limited: 'Troppe domande. Riprova tra un’ora.', failed: 'Non inviato. Riprova.', website: 'Non compilare' },
} satisfies Record<Language, Record<string, string>>

const empty = { kind: 'SELF' as 'SELF' | 'CHILD', firstName: '', lastName: '', dateOfBirth: '', email: '', phone: '',
  parentFirstName: '', parentLastName: '', teamId: '', message: '', privacy: false, website: '' }

/** The public membership application (OpenAPI 1.22.0), shown only while the club has opened it. */
export function JoinForm({ language }: { language: Language }) {
  const t = words[language]
  const [form, setForm] = useState<Form | null>(null)
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState(empty)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<'sent' | 'invalid' | 'closed' | 'full' | 'limited' | 'failed' | null>(null)

  useEffect(() => {
    let active = true
    readJoinForm().then(result => { if (active) setForm(result) }, () => { /* no form when unavailable */ })
    return () => { active = false }
  }, [])

  if (!form?.open) return null
  const child = draft.kind === 'CHILD'
  async function send(event: FormEvent) {
    event.preventDefault()
    setBusy(true); setNotice(null)
    try {
      const outcome = await submitJoinRequest({
        kind: draft.kind, firstName: draft.firstName, lastName: draft.lastName, dateOfBirth: draft.dateOfBirth,
        email: draft.email, phone: draft.phone, parentFirstName: child ? draft.parentFirstName : null,
        parentLastName: child ? draft.parentLastName : null, teamId: draft.teamId || null, message: draft.message.trim() || null,
      }, fetch, draft.website)
      if (outcome === 'ok') { setDraft(empty); setNotice('sent') } else setNotice(outcome)
    } catch { setNotice('failed') } finally { setBusy(false) }
  }
  const field = (key: keyof typeof empty, label: string, type = 'text') => <label>{label}
    <input type={type} required value={draft[key] as string} onChange={event => setDraft({ ...draft, [key]: event.target.value })} /></label>
  return (
    <section aria-label={t.title}>
      <button type="button" aria-expanded={open} onClick={() => setOpen(!open)}>{t.open}</button>
      {open && <form onSubmit={event => { void send(event) }}>
        <h3>{t.title}</h3>
        <fieldset><legend>{t.title}</legend>
          <label><input type="radio" name="kind" checked={!child} onChange={() => setDraft({ ...draft, kind: 'SELF' })} /> {t.self}</label>
          <label><input type="radio" name="kind" checked={child} onChange={() => setDraft({ ...draft, kind: 'CHILD' })} /> {t.child}</label>
        </fieldset>
        {field('firstName', child ? t.childFirstName : t.firstName)}
        {field('lastName', child ? t.childLastName : t.lastName)}
        {field('dateOfBirth', t.birth, 'date')}
        {child && field('parentFirstName', t.parentFirstName)}
        {child && field('parentLastName', t.parentLastName)}
        {field('email', t.email, 'email')}
        {field('phone', t.phone, 'tel')}
        <label>{t.team}<select value={draft.teamId} onChange={event => setDraft({ ...draft, teamId: event.target.value })}>
          <option value="">{t.none}</option>
          {form.teams.map(team => <option key={team.id} value={team.id}>{team.name}</option>)}
        </select></label>
        <label>{t.message}<textarea maxLength={500} value={draft.message} onChange={event => setDraft({ ...draft, message: event.target.value })} /></label>
        <label><input type="checkbox" required checked={draft.privacy} onChange={event => setDraft({ ...draft, privacy: event.target.checked })} /> {t.privacy}</label>
        {/* Honeypot: hidden from people and assistive technology; a bot that fills it is dropped by the server. */}
        <div aria-hidden="true" style={{ position: 'absolute', left: '-10000px', width: 1, height: 1, overflow: 'hidden' }}>
          <label>{t.website}<input tabIndex={-1} autoComplete="off" value={draft.website}
            onChange={event => setDraft({ ...draft, website: event.target.value })} /></label>
        </div>
        <button type="submit" disabled={busy}>{t.send}</button>
      </form>}
      {notice && <p role={notice === 'sent' ? 'status' : 'alert'}>{t[notice]}</p>}
    </section>
  )
}
