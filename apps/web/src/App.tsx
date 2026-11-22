import { useEffect, useState } from 'react'
import { loadClubAccess, signOut, type ClubAccess } from './auth'
import { readPreferredLanguage, type Language } from './language'
import { loadAccess, hasAdmin, noAccess, type Access } from './access'
import { parseRoute, routeHash, tabOf, type Route } from './routes'
import { fetchMyProfile } from './myProfile'
import { PublicMembers } from './PublicMembers'
import { JoinForm } from './JoinForm'
import { TeamDirectory } from './TeamDirectory'
import { HelpersMissing } from './HelpersMissing'
import { AcceptInvitation } from './AcceptInvitation'
import { StartPage } from './StartPage'
import { SchedulePage } from './SchedulePage'
import { EventPage } from './EventPage'
import { FamilyPage } from './FamilyPage'
import { AdminPage, type ClubState } from './AdminPage'
import { secretFromHash } from './invitations'

const translations = {
  de: { loading: 'Anmeldung wird geprüft…', signedOut: 'Bitte anmelden.', notMember: 'Für dieses Konto besteht noch keine Vereinsmitgliedschaft.', ready: 'Angemeldet und für diesen Verein berechtigt.', unavailable: 'Dienst momentan nicht verfügbar.', retry: 'Erneut versuchen', login: 'Anmelden', logout: 'Abmelden', loggingOut: 'Abmeldung läuft…', error: 'Abmelden fehlgeschlagen.', language: 'Sprache', heading: 'Mitgliederbereich', intro: 'Termine, Antworten und Fahrten für Mitglieder und Eltern.', start: 'Start', schedule: 'Termine', family: 'Familie', team: 'Team', admin: 'Verwaltung', main: 'Hauptnavigation' },
  en: { loading: 'Checking sign-in…', signedOut: 'Please sign in.', notMember: 'This account is not yet a club member.', ready: 'Signed in and authorized for this club.', unavailable: 'Service temporarily unavailable.', retry: 'Try again', login: 'Sign in', logout: 'Sign out', loggingOut: 'Signing out…', error: 'Could not sign out.', language: 'Language', heading: 'Members area', intro: 'Events, answers and rides for members and parents.', start: 'Start', schedule: 'Schedule', family: 'Family', team: 'Team', admin: 'Administration', main: 'Main navigation' },
  fr: { loading: 'Vérification de connexion…', signedOut: 'Veuillez vous connecter.', notMember: 'Ce compte ne fait pas encore partie du club.', ready: 'Connecté et autorisé pour ce club.', unavailable: 'Service temporairement indisponible.', retry: 'Réessayer', login: 'Se connecter', logout: 'Se déconnecter', loggingOut: 'Déconnexion en cours…', error: 'Déconnexion impossible.', language: 'Langue', heading: 'Espace membres', intro: 'Événements, réponses et covoiturage pour membres et parents.', start: 'Accueil', schedule: 'Agenda', family: 'Famille', team: 'Équipe', admin: 'Administration', main: 'Navigation principale' },
  it: { loading: 'Verifica accesso…', signedOut: 'Effettua l’accesso.', notMember: 'Questo account non è ancora membro del club.', ready: 'Accesso effettuato e autorizzato per questo club.', unavailable: 'Servizio temporaneamente non disponibile.', retry: 'Riprova', login: 'Accedi', logout: 'Esci', loggingOut: 'Disconnessione…', error: 'Impossibile disconnettersi.', language: 'Lingua', heading: 'Area membri', intro: 'Eventi, risposte e passaggi per membri e genitori.', start: 'Inizio', schedule: 'Calendario', family: 'Famiglia', team: 'Squadra', admin: 'Amministrazione', main: 'Navigazione principale' },
} satisfies Record<Language, Record<string, string>>

type Tab = ReturnType<typeof tabOf>

/**
 * The app shell: a header with the club's name, language and sign-out, navigation by task (Start, Schedule, Family,
 * and Team or Administration only for people who can use them), and one page at a time. Each page loads its own data.
 */
export function App() {
  const [language, setLanguage] = useState<Language>(readPreferredLanguage)
  const [access, setAccess] = useState<ClubAccess | null>(null)
  const [rights, setRights] = useState<Access | null>(null)
  const [self, setSelf] = useState<string | null>(null)
  const [route, setRoute] = useState<Route>(() => parseRoute(window.location.hash))
  const [error, setError] = useState(false)
  const [loggingOut, setLoggingOut] = useState(false)
  const [directoryRevision, setDirectoryRevision] = useState(0)
  // An invitation link carries its secret in the fragment; keep it for the sign-in round trip, then drop it from the URL.
  const [invite] = useState(() => {
    const secret = secretFromHash(window.location.hash)
    try {
      if (secret) window.sessionStorage.setItem('openclub.invite', secret)
      return secret ?? window.sessionStorage.getItem('openclub.invite') ?? ''
    } catch { return secret ?? '' }
  })
  const words = translations[language]

  useEffect(() => {
    let active = true
    void loadClubAccess().then(result => { if (active) setAccess(result) })
    return () => { active = false }
  }, [])

  const ready = access?.state === 'ready'
  useEffect(() => {
    if (!ready) { setRights(null); setSelf(null); return }
    let active = true
    void loadAccess().then(result => { if (active) setRights(result) })
    fetchMyProfile().then(result => { if (active) setSelf(result === 'denied' ? null : result.profile.id) })
      .catch(() => { if (active) setSelf(null) })
    return () => { active = false }
  }, [ready])

  useEffect(() => {
    if (secretFromHash(window.location.hash)) window.history.replaceState(null, '', window.location.pathname + window.location.search)
    const follow = () => { setRoute(parseRoute(window.location.hash)); window.scrollTo(0, 0) }
    window.addEventListener('hashchange', follow)
    return () => window.removeEventListener('hashchange', follow)
  }, [])

  useEffect(() => {
    document.documentElement.lang = language
    try {
      window.localStorage.setItem('openclub.language', language)
    } catch {
      // Browsers may disable storage; the selector still works for this visit.
    }
  }, [language])

  useEffect(() => {
    const club = access?.state === 'ready' ? access.clubName : words.heading
    const page = route.page === 'event' ? words.schedule : words[tabOf(route)]
    document.title = route.page === 'start' ? club : `${page} · ${club}`
  }, [access, route, words])

  async function retryAccess() {
    setError(false)
    setAccess(null)
    setAccess(await loadClubAccess())
  }

  async function logout() {
    if (loggingOut || (access?.state !== 'ready' && access?.state !== 'not-member')) return
    setLoggingOut(true)
    setError(false)
    if (await signOut(access.csrfToken)) { setAccess({ state: 'signed-out' }); window.location.hash = '' }
    else setError(true)
    setLoggingOut(false)
  }

  const message = !access ? words.loading
    : access.state === 'signed-out' ? words.signedOut
    : access.state === 'not-member' ? words.notMember
    : access.state === 'ready' ? words.ready : words.unavailable

  const can = rights ?? noAccess
  const tabs: Tab[] = ['start', 'schedule', 'family', ...(can.teams ? ['team' as const] : []), ...(hasAdmin(can) ? ['admin' as const] : [])]
  // Team and Administration wait for the rights check; a page the person cannot use (an old link) falls back to Start.
  const waiting = rights === null && (route.page === 'team' || route.page === 'admin')
  const shown: Route = tabs.includes(tabOf(route)) || waiting ? route : { page: 'start' }
  const updateClub = (change: Partial<ClubState>) => setAccess(current => current?.state === 'ready' ? { ...current, ...change } : current)

  return (
    <div className={ready ? 'app signed-in' : 'app'}>
      <header className="top">
        {ready ? <a className="club" href={routeHash({ page: 'start' })}>{access.clubName}</a> : <span className="club">{words.heading}</span>}
        <label className="language"><span className="vh">{words.language}</span>
          <select id="language" value={language} onChange={e => setLanguage(e.target.value as Language)}>
            <option value="de">DE</option><option value="fr">FR</option><option value="it">IT</option><option value="en">EN</option>
          </select></label>
        {(access?.state === 'ready' || access?.state === 'not-member') && <button type="button" className="btn" disabled={loggingOut}
          onClick={() => { void logout() }}>{loggingOut ? words.loggingOut : words.logout}</button>}
      </header>
      {ready && <nav aria-label={words.main} className="tabs"><ul>
        {tabs.map(tab => <li key={tab}><a href={routeHash(tab === 'admin' ? { page: 'admin', section: 'overview' } : { page: tab } as Route)}
          aria-current={tabOf(shown) === tab ? 'page' : undefined}>{words[tab]}</a></li>)}
      </ul></nav>}
      <main className="page">
        <p role="status" className={ready ? 'vh' : 'status'}>{message}</p>
        {error && <p role="alert">{words.error}</p>}
        {access?.state === 'unavailable' && <button type="button" className="btn" onClick={() => { void retryAccess() }}>{words.retry}</button>}
        {access?.state === 'signed-out' && <>
          <h1>{words.heading}</h1>
          <p className="muted">{words.intro}</p>
          <a className="btn primary big" href="/api/v1/auth/login">{words.login}</a>
          <JoinForm language={language} />
          <PublicMembers language={language} revision={directoryRevision} />
        </>}
        {access?.state === 'not-member' && <AcceptInvitation language={language} csrfToken={access.csrfToken} initial={invite}
          onAccepted={() => { try { window.sessionStorage.removeItem('openclub.invite') } catch { /* storage may be disabled */ } void retryAccess() }} />}
        {ready && shown.page === 'start' && <StartPage language={language} csrfToken={access.csrfToken} self={self} />}
        {ready && shown.page === 'schedule' && <SchedulePage language={language} csrfToken={access.csrfToken} self={self} />}
        {ready && shown.page === 'event' && <EventPage key={shown.eventId} eventId={shown.eventId} language={language} csrfToken={access.csrfToken} self={self} />}
        {ready && shown.page === 'family' && <FamilyPage language={language} csrfToken={access.csrfToken} self={self} />}
        {ready && waiting && <p role="status">{words.loading}</p>}
        {ready && !waiting && shown.page === 'team' && <><h1>{words.team}</h1><HelpersMissing language={language} /><TeamDirectory language={language} csrfToken={access.csrfToken} /></>}
        {ready && !waiting && shown.page === 'admin' && <AdminPage section={shown.section} language={language} csrfToken={access.csrfToken}
          access={can} club={access} onClubChanged={updateClub} revision={directoryRevision}
          onDirectoryChanged={() => setDirectoryRevision(value => value + 1)} />}
      </main>
    </div>
  )
}
