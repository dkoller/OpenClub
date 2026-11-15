import { useEffect, useState } from 'react'
import { loadClubAccess, signOut, type ClubAccess } from './auth'
import { readPreferredLanguage, type Language } from './language'

const translations = {
  de: { heading: 'Willkommen bei OpenClub', intro: 'Eine Installation für einen Verein.', loading: 'Anmeldung wird geprüft…', signedOut: 'Bitte anmelden.', notMember: 'Für dieses Konto besteht noch keine Vereinsmitgliedschaft.', ready: 'Angemeldet und für diesen Verein berechtigt.', unavailable: 'Dienst momentan nicht verfügbar.', login: 'Anmelden', logout: 'Abmelden', error: 'Abmelden fehlgeschlagen.', language: 'Sprache' },
  en: { heading: 'Welcome to OpenClub', intro: 'One installation for one club.', loading: 'Checking sign-in…', signedOut: 'Please sign in.', notMember: 'This account is not yet a club member.', ready: 'Signed in and authorized for this club.', unavailable: 'Service temporarily unavailable.', login: 'Sign in', logout: 'Sign out', error: 'Could not sign out.', language: 'Language' },
  fr: { heading: 'Bienvenue sur OpenClub', intro: 'Une installation pour un club.', loading: 'Vérification de connexion…', signedOut: 'Veuillez vous connecter.', notMember: 'Ce compte ne fait pas encore partie du club.', ready: 'Connecté et autorisé pour ce club.', unavailable: 'Service temporairement indisponible.', login: 'Se connecter', logout: 'Se déconnecter', error: 'Déconnexion impossible.', language: 'Langue' },
  it: { heading: 'Benvenuti in OpenClub', intro: 'Una installazione per un club.', loading: 'Verifica accesso…', signedOut: 'Effettua l’accesso.', notMember: 'Questo account non è ancora membro del club.', ready: 'Accesso effettuato e autorizzato per questo club.', unavailable: 'Servizio temporaneamente non disponibile.', login: 'Accedi', logout: 'Esci', error: 'Impossibile disconnettersi.', language: 'Lingua' },
} satisfies Record<Language, Record<string, string>>

export function App() {
  const [language, setLanguage] = useState<Language>(readPreferredLanguage)
  const [access, setAccess] = useState<ClubAccess | null>(null)
  const [error, setError] = useState(false)
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

  async function logout() {
    if (access?.state !== 'ready') return
    setError(false)
    if (await signOut(access.csrfToken)) setAccess({ state: 'signed-out' })
    else setError(true)
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
      <p role="status">{message}</p>
      {access?.state === 'signed-out' && <a href="/api/v1/auth/login">{words.login}</a>}
      {access?.state === 'ready' && <button type="button" onClick={() => { void logout() }}>{words.logout}</button>}
      {error && <p role="alert">{words.error}</p>}
    </main>
  )
}
