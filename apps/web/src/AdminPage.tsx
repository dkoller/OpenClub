import { useEffect, useState } from 'react'
import { BackLink } from './BackLink'
import type { Language } from './language'
import type { Access } from './access'
import { routeHash, type AdminSection, type MemberPart } from './routes'
import { readJoinRequests } from './joinRequests'
import { formatFrancs, readPeriods, type FeePeriod } from './fees'
import { MemberAdmin } from './MemberAdmin'
import { ArchivedMembers } from './ArchivedMembers'
import { GuardianAdmin } from './GuardianAdmin'
import { InvitationAdmin } from './InvitationAdmin'
import { MemberListTransfer } from './MemberListTransfer'
import { JoinRequestsAdmin } from './JoinRequestsAdmin'
import { FeeAdmin } from './FeeAdmin'
import { HelperDutyAdmin } from './HelperDuty'
import { GrantAdmin } from './GrantAdmin'
import { RoleAdmin } from './RoleAdmin'
import { ClubSettings } from './ClubSettings'
import { fill } from './EventBits'
import { PublicMembers } from './PublicMembers'
import { readUnfilled, type UnfilledShift } from './shifts'
import { describeShift, helperWords } from './HelpersMissing'
import { VenueAdmin } from './VenuesPage'

const words = {
  de: { venues: 'Plätze und Hallen', nav: 'Verwaltung', overview: 'Wartet auf dich', members: 'Mitglieder', 'join-requests': 'Beitrittsanfragen', fees: 'Beiträge', permissions: 'Berechtigungen', settings: 'Einstellungen', nothing: 'Im Moment wartet nichts auf dich.', requests: '{n} Beitrittsanfragen', requestsOne: '1 Beitrittsanfrage', since: 'Älteste vom {date}', openFees: '{period}: {n} Beiträge offen', amount: '{amount} von {total} offen, fällig am {date}', view: 'Ansehen', toTeam: 'Zum Team', loading: 'Wird geladen…', error: 'Nicht alles konnte geladen werden.' },
  en: { venues: 'Fields and halls', nav: 'Administration', overview: 'Waiting for you', members: 'Members', 'join-requests': 'Join requests', fees: 'Fees', permissions: 'Permissions', settings: 'Settings', nothing: 'Nothing is waiting for you right now.', requests: '{n} join requests', requestsOne: '1 join request', since: 'Oldest from {date}', openFees: '{period}: {n} fees open', amount: '{amount} of {total} open, due {date}', view: 'View', toTeam: 'Open team', loading: 'Loading…', error: 'Not everything could be loaded.' },
  fr: { venues: 'Terrains et salles', nav: 'Administration', overview: 'En attente', members: 'Membres', 'join-requests': 'Demandes d’adhésion', fees: 'Cotisations', permissions: 'Autorisations', settings: 'Réglages', nothing: 'Rien ne vous attend pour le moment.', requests: '{n} demandes d’adhésion', requestsOne: '1 demande d’adhésion', since: 'La plus ancienne du {date}', openFees: '{period} : {n} cotisations ouvertes', amount: '{amount} sur {total} ouverts, échéance le {date}', view: 'Voir', toTeam: 'Vers l’équipe', loading: 'Chargement…', error: 'Tout n’a pas pu être chargé.' },
  it: { venues: 'Campi e palestre', nav: 'Amministrazione', overview: 'In attesa', members: 'Membri', 'join-requests': 'Richieste di adesione', fees: 'Quote', permissions: 'Autorizzazioni', settings: 'Impostazioni', nothing: 'Al momento non c’è niente in attesa.', requests: '{n} richieste di adesione', requestsOne: '1 richiesta di adesione', since: 'La più vecchia del {date}', openFees: '{period}: {n} quote aperte', amount: '{amount} su {total} aperti, scadenza {date}', view: 'Vedi', toTeam: 'Alla squadra', loading: 'Caricamento…', error: 'Non è stato possibile caricare tutto.' },
} satisfies Record<Language, Record<string, string>>

const partWords = {
  de: { more: 'Weitere Aufgaben', back: 'Mitglieder', invitations: 'Einladungen und Konten', guardians: 'Erziehungsberechtigte', roles: 'Rollen im Verein', transfer: 'Excel-Import und -Export', archived: 'Archiv', public: 'Öffentliche Mitgliederliste' },
  en: { more: 'More tasks', back: 'Members', invitations: 'Invitations and accounts', guardians: 'Guardians', roles: 'Club roles', transfer: 'Excel import and export', archived: 'Archive', public: 'Public member list' },
  fr: { more: 'Autres tâches', back: 'Membres', invitations: 'Invitations et comptes', guardians: 'Représentants légaux', roles: 'Rôles dans le club', transfer: 'Import et export Excel', archived: 'Archives', public: 'Liste publique des membres' },
  it: { more: 'Altre attività', back: 'Membri', invitations: 'Inviti e account', guardians: 'Tutori', roles: 'Ruoli nel club', transfer: 'Importazione ed esportazione Excel', archived: 'Archivio', public: 'Elenco pubblico dei membri' },
} satisfies Record<Language, Record<string, string>>

/** The member tasks this person may use, each on its own page. */
export function memberParts(access: Access): MemberPart[] {
  return [
    ...(access.editor ? ['invitations' as const, 'guardians' as const] : []),
    ...(access.permissions ? ['roles' as const] : []),
    ...(access.editor ? ['transfer' as const, 'archived' as const] : []),
    'public',
  ]
}

/** The sections this person may use, in sidebar order. */
export function adminSections(access: Access): AdminSection[] {
  return [
    'overview',
    ...(access.editor || access.directory || access.permissions ? ['members' as const] : []),
    ...(access.editor ? ['join-requests' as const] : []),
    ...(access.fees ? ['fees' as const] : []),
    ...(access.venues ? ['venues' as const] : []),
    ...(access.permissions ? ['permissions' as const, 'settings' as const] : []),
  ]
}

const swissDate = (date: string) => date.slice(8, 10) + '.' + date.slice(5, 7) + '.' + date.slice(0, 4)

function Overview({ language, access }: { language: Language; access: Access }) {
  const t = words[language]
  const [requests, setRequests] = useState<{ count: number; oldest: string | null } | null>(null)
  const [periods, setPeriods] = useState<FeePeriod[]>([])
  const [shifts, setShifts] = useState<UnfilledShift[]>([])
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading')
  useEffect(() => {
    let active = true
    void Promise.all([
      access.editor ? readJoinRequests() : Promise.resolve('denied' as const),
      access.fees ? readPeriods() : Promise.resolve('denied' as const),
      access.teams ? readUnfilled() : Promise.resolve('denied' as const),
    ]).then(([joins, fees, unfilled]) => {
      if (!active) return
      setRequests(joins === 'denied' ? null : { count: joins.length, oldest: joins[0]?.createdAt.slice(0, 10) ?? null })
      setPeriods(fees === 'denied' ? [] : fees.filter(period => period.paid < period.members))
      setShifts(unfilled === 'denied' ? [] : unfilled.items)
      setState('ready')
    }).catch(() => { if (active) setState('error') })
    return () => { active = false }
  }, [access.editor, access.fees, access.teams])
  const items = [
    ...(requests && requests.count > 0 ? [{ key: 'join', title: requests.count === 1 ? t.requestsOne : fill(t.requests, { n: requests.count }),
      detail: requests.oldest ? fill(t.since, { date: swissDate(requests.oldest) }) : '', tag: t['join-requests'],
      href: routeHash({ page: 'admin', section: 'join-requests' }), action: t.view }] : []),
    ...shifts.map(shift => ({ key: shift.id, ...describeShift(shift, language), tag: helperWords[language].title,
      href: routeHash({ page: 'team' }), action: t.toTeam })),
    ...periods.map(period => ({ key: period.id, title: fill(t.openFees, { period: period.name, n: period.members - period.paid }),
      detail: fill(t.amount, { amount: formatFrancs(period.totalCents - period.paidCents, language), total: formatFrancs(period.totalCents, language), date: swissDate(period.dueDate) }),
      tag: t.fees, href: routeHash({ page: 'admin', section: 'fees' }), action: t.view })),
  ]
  return <section aria-labelledby="waiting" className="stack">
    <h1 id="waiting">{t.overview}</h1>
    {state === 'loading' && <p role="status">{t.loading}</p>}
    {state === 'error' && <p role="alert">{t.error}</p>}
    {state === 'ready' && items.length === 0 && <p className="muted">{t.nothing}</p>}
    {items.map(item => <article key={item.key} className="card pad todo" aria-label={item.title}>
      <div><span className="open">{item.tag}</span><h2>{item.title}</h2><p className="muted">{item.detail}</p></div>
      <a className="btn primary" href={item.href}>{item.action}</a>
    </article>)}
  </section>
}

export type ClubState = { clubName: string; genderRequiredForPlayers: boolean; adultAgeYears: number }

/** Club administration: a sidebar of the sections this person may use, one section at a time. */
export function AdminPage({ section, part, language, csrfToken, access, club, onClubChanged, onDirectoryChanged, revision }: {
  section: AdminSection; part?: MemberPart; language: Language; csrfToken: string; access: Access; club: ClubState
  onClubChanged: (change: Partial<ClubState>) => void; onDirectoryChanged: () => void; revision: number
}) {
  const t = words[language]
  const sections = adminSections(access)
  const current = sections.includes(section) ? section : 'overview'
  const p = partWords[language]
  const parts = memberParts(access)
  const task = part && parts.includes(part) ? part : undefined
  return <div className="admin">
    <nav aria-label={t.nav} className="admin-nav"><ul>
      {sections.map(item => <li key={item}><a href={routeHash({ page: 'admin', section: item })}
        aria-current={item === current ? 'page' : undefined}>{t[item]}</a></li>)}
    </ul></nav>
    <div className="admin-main">
      {current === 'overview' && <Overview language={language} access={access} />}
      {current === 'members' && !task && <>
        <h1>{t.members}</h1>
        <nav aria-label={p.more}><ul className="chips plain">{parts.map(item => <li key={item}>
          <a className="btn chip" href={routeHash({ page: 'admin', section: 'members', part: item })}>{p[item]}</a></li>)}</ul></nav>
        <MemberAdmin language={language} csrfToken={csrfToken} genderRequiredForPlayers={club.genderRequiredForPlayers}
          adultAgeYears={club.adultAgeYears} onChanged={onDirectoryChanged} />
      </>}
      {current === 'members' && task && <>
        <BackLink href={routeHash({ page: 'admin', section: 'members' })} label={p.back} />
        <h1>{p[task]}</h1>
        {task === 'invitations' && <InvitationAdmin language={language} csrfToken={csrfToken} />}
        {task === 'guardians' && <GuardianAdmin language={language} csrfToken={csrfToken} />}
        {task === 'roles' && <RoleAdmin language={language} csrfToken={csrfToken} />}
        {task === 'transfer' && <MemberListTransfer language={language} csrfToken={csrfToken} />}
        {task === 'archived' && <ArchivedMembers language={language} csrfToken={csrfToken} revision={revision} onChanged={onDirectoryChanged} />}
        {task === 'public' && <PublicMembers language={language} revision={revision} />}
      </>}
      {current === 'join-requests' && <><h1>{t['join-requests']}</h1>
        <JoinRequestsAdmin language={language} csrfToken={csrfToken} onChanged={onDirectoryChanged} /></>}
      {current === 'fees' && <><h1>{t.fees}</h1><FeeAdmin language={language} csrfToken={csrfToken} />
        <HelperDutyAdmin language={language} csrfToken={csrfToken} /></>}
      {current === 'venues' && <><h1>{t.venues}</h1><VenueAdmin language={language} csrfToken={csrfToken} /></>}
      {current === 'permissions' && <><h1>{t.permissions}</h1><GrantAdmin language={language} csrfToken={csrfToken} /></>}
      {current === 'settings' && <><h1>{t.settings}</h1>
        <ClubSettings key={csrfToken} language={language} name={club.clubName} csrfToken={csrfToken}
          genderRequiredForPlayers={club.genderRequiredForPlayers} adultAgeYears={club.adultAgeYears}
          onPolicyChanged={required => onClubChanged({ genderRequiredForPlayers: required })}
          onAdultAgeChanged={years => onClubChanged({ adultAgeYears: years })}
          onChanged={name => onClubChanged({ clubName: name })} /></>}
    </div>
  </div>
}
