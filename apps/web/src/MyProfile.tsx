import { useEffect, useState, type FormEvent } from 'react'
import type { Language } from './language'
import { fetchMyProfile, replaceMyContacts, setMyPublicListing, type MyProfile as Profile } from './myProfile'

const words = {
  de: { listed: 'Meinen Namen in der öffentlichen Mitgliederliste zeigen', listedHint: 'Die öffentliche Liste zeigt nur den Namen (bei Kindern Vorname und Initiale).', listedSaved: 'Einstellung gespeichert.', title: 'Mein Profil', name: 'Name', dob: 'Geburtsdatum', gender: 'Geschlecht', player: 'Spieler', yes: 'Ja', no: 'Nein', role: 'Rolle', ADMIN: 'Administrator', MEMBER: 'Mitglied', teams: 'Teams', none: 'keine', email: 'E-Mail', phone: 'Telefon', save: 'Kontaktdaten speichern', saved: 'Kontaktdaten gespeichert.', invalid: 'Bitte E-Mail und Telefon prüfen. Erwachsene brauchen beides.', changed: 'Nicht gespeichert: Dein Profil wurde inzwischen geändert. Die aktuellen Werte sind jetzt angezeigt.', hint: 'Andere Angaben ändert die Vereinsverwaltung.', error: 'Nicht verfügbar.' },
  en: { listed: 'Show my name on the public member list', listedHint: 'The public list shows only the name (for children, first name and initial).', listedSaved: 'Setting saved.', title: 'My profile', name: 'Name', dob: 'Date of birth', gender: 'Gender', player: 'Player', yes: 'Yes', no: 'No', role: 'Role', ADMIN: 'Administrator', MEMBER: 'Member', teams: 'Teams', none: 'none', email: 'Email', phone: 'Phone', save: 'Save contacts', saved: 'Contacts saved.', invalid: 'Please check the email and phone. Adults need both.', changed: 'Not saved: your profile was changed in the meantime. The current values are shown now.', hint: 'Other details are changed by the club administration.', error: 'Unavailable.' },
  fr: { listed: 'Afficher mon nom dans la liste publique des membres', listedHint: 'La liste publique ne montre que le nom (pour les enfants, prénom et initiale).', listedSaved: 'Réglage enregistré.', title: 'Mon profil', name: 'Nom', dob: 'Date de naissance', gender: 'Genre', player: 'Joueur', yes: 'Oui', no: 'Non', role: 'Rôle', ADMIN: 'Administrateur', MEMBER: 'Membre', teams: 'Équipes', none: 'aucune', email: 'E-mail', phone: 'Téléphone', save: 'Enregistrer les coordonnées', saved: 'Coordonnées enregistrées.', invalid: 'Vérifiez l’e-mail et le téléphone. Les adultes ont besoin des deux.', changed: 'Non enregistré : votre profil a été modifié entre-temps. Les valeurs actuelles sont maintenant affichées.', hint: 'Les autres informations sont modifiées par l’administration du club.', error: 'Indisponible.' },
  it: { listed: 'Mostra il mio nome nell’elenco pubblico dei membri', listedHint: 'L’elenco pubblico mostra solo il nome (per i bambini, nome e iniziale).', listedSaved: 'Impostazione salvata.', title: 'Il mio profilo', name: 'Nome', dob: 'Data di nascita', gender: 'Genere', player: 'Giocatore', yes: 'Sì', no: 'No', role: 'Ruolo', ADMIN: 'Amministratore', MEMBER: 'Membro', teams: 'Squadre', none: 'nessuna', email: 'Email', phone: 'Telefono', save: 'Salva contatti', saved: 'Contatti salvati.', invalid: 'Controlla email e telefono. Gli adulti hanno bisogno di entrambi.', changed: 'Non salvato: il tuo profilo è stato modificato nel frattempo. Ora sono mostrati i valori attuali.', hint: 'Gli altri dati sono modificati dall’amministrazione del club.', error: 'Non disponibile.' },
} satisfies Record<Language, Record<string, string>>

/**
 * The signed-in member's own record (OpenAPI 1.9.0). The contact form starts from the read and sends its version,
 * so neither the member nor an editor silently overwrites the other's change. Shown on its own page, #/family/profile.
 */
export function MyProfile({ language, csrfToken }: { language: Language; csrfToken: string }) {
  const t = words[language]
  const [profile, setProfile] = useState<Profile | null>(null)
  const [version, setVersion] = useState('')
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [notice, setNotice] = useState<'saved' | 'listedSaved' | 'invalid' | 'changed' | 'error' | null>(null)
  const [busy, setBusy] = useState(false)

  async function load(): Promise<boolean> {
    try {
      const result = await fetchMyProfile()
      if (result === 'denied') { setProfile(null); return false }
      setProfile(result.profile)
      setVersion(result.version)
      setEmail(result.profile.email ?? '')
      setPhone(result.profile.phone ?? '')
      return true
    } catch { setNotice('error'); return false }
  }
  useEffect(() => { void load() }, [])

  async function save(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setNotice(null)
    try {
      const outcome = await replaceMyContacts(email, phone, version, csrfToken)
      if (outcome === 'ok') { if (await load()) setNotice('saved') }
      else if (outcome === 'changed') { if (await load()) setNotice('changed') }
      else if (outcome === 'invalid') setNotice('invalid')
      else setProfile(null)
    } catch { setNotice('error') } finally { setBusy(false) }
  }

  /** OpenAPI 1.15.0: the member's own objection to the public list (controller decision C5). */
  async function switchListing(listed: boolean) {
    setBusy(true)
    setNotice(null)
    try {
      const result = await setMyPublicListing(listed, version, csrfToken)
      if (result.outcome === 'ok') { if (await load()) setNotice('listedSaved') }
      else if (result.outcome === 'changed') { if (await load()) setNotice('changed') }
      else if (result.outcome === 'invalid') setNotice('error')
      else setProfile(null)
    } catch { setNotice('error') } finally { setBusy(false) }
  }

  if (!profile) return notice === 'error' ? <section aria-label={t.title}><h1>{t.title}</h1><p role="alert">{t.error}</p></section> : null
  return (
    <section aria-label={t.title}>
      <h1>{t.title}</h1>
      <dl>
        <div><dt>{t.name}</dt><dd>{profile.firstName} {profile.lastName}</dd></div>
        <div><dt>{t.dob}</dt><dd>{profile.dateOfBirth}</dd></div>
        {profile.gender && <div><dt>{t.gender}</dt><dd>{profile.gender}</dd></div>}
        <div><dt>{t.player}</dt><dd>{profile.isPlayer ? t.yes : t.no}</dd></div>
        <div><dt>{t.role}</dt><dd>{t[profile.role]}</dd></div>
        <div><dt>{t.teams}</dt><dd>{profile.teams.length ? profile.teams.map(team => team.name).join(', ') : t.none}</dd></div>
      </dl>
      <form onSubmit={event => { void save(event) }}>
        <label>{t.email}<input type="email" value={email} maxLength={320} autoComplete="email" onChange={event => setEmail(event.target.value)} /></label>
        <label>{t.phone}<input type="tel" value={phone} maxLength={40} autoComplete="tel" onChange={event => setPhone(event.target.value)} /></label>
        <button type="submit" disabled={busy}>{t.save}</button>
      </form>
      <label><input type="checkbox" checked={profile.listedPublicly} disabled={busy}
        onChange={event => { void switchListing(event.target.checked) }} /> {t.listed}</label>
      <p>{t.listedHint}</p>
      <p>{t.hint}</p>
      {notice && <p role={notice === 'error' ? 'alert' : 'status'}>{t[notice]}</p>}
    </section>
  )
}
