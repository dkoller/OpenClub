import { useEffect, useState } from 'react'
import { loadClubAccess, signOut, type ClubAccess } from './auth'
import { readPreferredLanguage, type Language } from './language'
import { PublicMembers } from './PublicMembers'
import { MemberAdmin } from './MemberAdmin'
import { TeamDirectory } from './TeamDirectory'
import { GrantAdmin } from './GrantAdmin'
import { ClubSettings } from './ClubSettings'
import { RoleAdmin } from './RoleAdmin'

const translations = {
  de: { heading: 'Willkommen bei OpenClub', intro: 'Eine Installation für einen Verein.', loading: 'Anmeldung wird geprüft…', signedOut: 'Bitte anmelden.', notMember: 'Für dieses Konto besteht noch keine Vereinsmitgliedschaft.', ready: 'Angemeldet und für diesen Verein berechtigt.', unavailable: 'Dienst momentan nicht verfügbar.', retry: 'Erneut versuchen', login: 'Anmelden', logout: 'Abmelden', loggingOut: 'Abmeldung läuft…', error: 'Abmelden fehlgeschlagen.', language: 'Sprache' },
  en: { heading: 'Welcome to OpenClub', intro: 'One installation for one club.', loading: 'Checking sign-in…', signedOut: 'Please sign in.', notMember: 'This account is not yet a club member.', ready: 'Signed in and authorized for this club.', unavailable: 'Service temporarily unavailable.', retry: 'Try again', login: 'Sign in', logout: 'Sign out', loggingOut: 'Signing out…', error: 'Could not sign out.', language: 'Language' },
  fr: { heading: 'Bienvenue sur OpenClub', intro: 'Une installation pour un club.', loading: 'Vérification de connexion…', signedOut: 'Veuillez vous connecter.', notMember: 'Ce compte ne fait pas encore partie du club.', ready: 'Connecté et autorisé pour ce club.', unavailable: 'Service temporairement indisponible.', retry: 'Réessayer', login: 'Se connecter', logout: 'Se déconnecter', loggingOut: 'Déconnexion en cours…', error: 'Déconnexion impossible.', language: 'Langue' },
  it: { heading: 'Benvenuti in OpenClub', intro: 'Una installazione per un club.', loading: 'Verifica accesso…', signedOut: 'Effettua l’accesso.', notMember: 'Questo account non è ancora membro del club.', ready: 'Accesso effettuato e autorizzato per questo club.', unavailable: 'Servizio temporaneamente non disponibile.', retry: 'Riprova', login: 'Accedi', logout: 'Esci', loggingOut: 'Disconnessione…', error: 'Impossibile disconnettersi.', language: 'Lingua' },
} satisfies Record<Language, Record<string, string>>

export function App() {
  const [language, setLanguage] = useState<Language>(readPreferredLanguage)
  const [access, setAccess] = useState<ClubAccess | null>(null)
  const [error, setError] = useState(false)
  const [loggingOut, setLoggingOut] = useState(false)
  const [directoryRevision, setDirectoryRevision] = useState(0)
  const words = translations[language]

  useEffect(() => {
    let active = true
    void loadClubAccess().then(result => { if (active) setAccess(result) })
    return () => { active = false }
  }, [])

  useEffect(() => {
    document.documentElement.lang = language
    try {
      window.localStorage.setItem('openclub.language', language)
    } catch {
      // Browsers may disable storage; the selector still works for this visit.
    }
  }, [language])

  async function retryAccess() {
    setError(false)
    setAccess(null)
    setAccess(await loadClubAccess())
  }

  async function logout() {
    if (loggingOut || (access?.state !== 'ready' && access?.state !== 'not-member')) return
    setLoggingOut(true)
    setError(false)
    if (await signOut(access.csrfToken)) setAccess({ state: 'signed-out' })
    else setError(true)
    setLoggingOut(false)
  }

  const message = !access ? words.loading
    : access.state === 'signed-out' ? words.signedOut
    : access.state === 'not-member' ? words.notMember
    : access.state === 'ready' ? words.ready : words.unavailable

  return (
    <main>
      <label htmlFor="language">{words.language}</label>
      <select id="language" value={language} onChange={e => setLanguage(e.target.value as Language)}>
        <option value="de">Deutsch</option><option value="en">English</option>
        <option value="fr">Français</option><option value="it">Italiano</option>
      </select>
      <h1>{words.heading}</h1>
      <p>{words.intro}</p>
      {access?.state === 'ready' && <h2>{access.clubName}</h2>}
      {access?.state === 'ready' && <ClubSettings key={access.csrfToken} language={language}
        name={access.clubName} csrfToken={access.csrfToken}
        onChanged={name => setAccess(current => current?.state === 'ready' ? { ...current, clubName: name } : current)} />}
      <PublicMembers language={language} revision={directoryRevision} />
      {access?.state === 'ready' && <GrantAdmin language={language} csrfToken={access.csrfToken} />}
      {access?.state === 'ready' && <RoleAdmin language={language} csrfToken={access.csrfToken} />}
      {access?.state === 'ready' && <TeamDirectory language={language} csrfToken={access.csrfToken} />}
      {access?.state === 'ready' && <MemberAdmin language={language} csrfToken={access.csrfToken} onChanged={() => setDirectoryRevision(value => value + 1)} /> }
      <p role="status">{message}</p>
      {access?.state === 'signed-out' && <a href="/api/v1/auth/login">{words.login}</a>}
      {access?.state === 'unavailable' && <button type="button" onClick={() => { void retryAccess() }}>{words.retry}</button>}
      {(access?.state === 'ready' || access?.state === 'not-member') && <button type="button" disabled={loggingOut} onClick={() => { void logout() }}>{loggingOut ? words.loggingOut : words.logout}</button>}
      {error && <p role="alert">{words.error}</p>}
    </main>
  )
}
