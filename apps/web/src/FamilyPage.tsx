import { useEffect, useState } from 'react'
import type { Language } from './language'
import type { Member } from './members'
import { routeHash, type FamilySection } from './routes'
import { listDependents } from './guardians'
import { readMyFees } from './fees'
import { fill } from './EventBits'
import { MyProfile } from './MyProfile'
import { MyDependents } from './MyDependents'
import { MyFees } from './MyFees'
import { MyHelperDutyCard } from './HelperDuty'
import { Absences } from './Absences'
import { CalendarFeed } from './CalendarFeed'
import { NotificationSettings } from './NotificationSettings'

const words = {
  de: { title: 'Familie', back: 'Familie', profile: 'Mein Profil', profileHint: 'Kontaktdaten und öffentliche Mitgliederliste', children: 'Meine Kinder', absences: 'Abwesenheiten', absencesHint: 'Ferien und freie Tage eintragen', fees: 'Beiträge', feesHint: 'Mitgliederbeiträge und ob sie bezahlt sind', open: '{n} offen', openOne: '1 offen', paid: 'Alles bezahlt', calendar: 'Kalender-Abo', calendarHint: 'Termine im Kalender des Telefons', notifications: 'E-Mail-Benachrichtigungen', notificationsHint: 'Welche E-Mails du erhältst' },
  en: { title: 'Family', back: 'Family', profile: 'My profile', profileHint: 'Contacts and the public member list', children: 'My children', absences: 'Absences', absencesHint: 'Enter holidays and days off', fees: 'Fees', feesHint: 'Membership fees and whether they are paid', open: '{n} open', openOne: '1 open', paid: 'All paid', calendar: 'Calendar subscription', calendarHint: 'Events in your phone’s calendar', notifications: 'Email notifications', notificationsHint: 'Which emails you receive' },
  fr: { title: 'Famille', back: 'Famille', profile: 'Mon profil', profileHint: 'Coordonnées et liste publique des membres', children: 'Mes enfants', absences: 'Absences', absencesHint: 'Saisir vacances et jours d’absence', fees: 'Cotisations', feesHint: 'Cotisations et si elles sont payées', open: '{n} ouvertes', openOne: '1 ouverte', paid: 'Tout est payé', calendar: 'Abonnement au calendrier', calendarHint: 'Les événements dans le calendrier du téléphone', notifications: 'Notifications par e-mail', notificationsHint: 'Quels e-mails vous recevez' },
  it: { title: 'Famiglia', back: 'Famiglia', profile: 'Il mio profilo', profileHint: 'Contatti ed elenco pubblico dei membri', children: 'I miei figli', absences: 'Assenze', absencesHint: 'Inserisci vacanze e giorni liberi', fees: 'Quote', feesHint: 'Quote sociali e se sono pagate', open: '{n} aperte', openOne: '1 aperta', paid: 'Tutto pagato', calendar: 'Abbonamento al calendario', calendarHint: 'Gli eventi nel calendario del telefono', notifications: 'Notifiche via email', notificationsHint: 'Quali email ricevi' },
} satisfies Record<Language, Record<string, string>>

type Props = { language: Language; csrfToken: string; self: string | null }

/**
 * Overview of me and my children: one large link per part, each with a one-line summary. Only two small reads (the
 * children's names, the open fees); every part loads its own data on its own page.
 */
function Overview({ language }: { language: Language }) {
  const t = words[language]
  const [children, setChildren] = useState<Member[]>([])
  const [fees, setFees] = useState<{ open: number; total: number } | null>(null)
  useEffect(() => {
    let active = true
    listDependents().then(result => { if (active) setChildren(result === 'denied' ? [] : result) }, () => undefined)
    readMyFees().then(result => {
      if (active) setFees(result === 'denied' ? null : { open: result.filter(fee => fee.paidOn === null).length, total: result.length })
    }, () => undefined)
    return () => { active = false }
  }, [])
  const open = fees?.open ?? 0
  const feeSummary = open > 1 ? fill(t.open, { n: open }) : open === 1 ? t.openOne : fees && fees.total > 0 ? t.paid : t.feesHint
  const rows: { section: FamilySection; title: string; summary: string; attention?: boolean }[] = [
    { section: 'profile', title: t.profile, summary: t.profileHint },
    ...(children.length > 0 ? [{ section: 'children' as const, title: t.children, summary: children.map(child => child.firstName).join(', ') }] : []),
    { section: 'absences', title: t.absences, summary: t.absencesHint },
    { section: 'fees', title: t.fees, summary: feeSummary, attention: open > 0 },
    { section: 'calendar', title: t.calendar, summary: t.calendarHint },
    { section: 'notifications', title: t.notifications, summary: t.notificationsHint },
  ]
  return <>
    <h1>{t.title}</h1>
    <div className="card family-list">
      <ul className="rows">{rows.map(row => <li key={row.section}>
        <a className="row family-row" href={routeHash({ page: 'family', section: row.section })}>
          <span className="event-text"><b>{row.title}</b>
            {row.attention ? <span><span className="open">{row.summary}</span></span> : <span className="muted">{row.summary}</span>}</span>
          <span className="chevron" aria-hidden="true">›</span>
        </a></li>)}</ul>
    </div>
  </>
}

/** Family: the overview at #/family, or one part on its own page with a link back. */
export function FamilyPage({ language, csrfToken, self, section }: Props & { section?: FamilySection }) {
  if (!section) return <Overview language={language} />
  return <>
    <p className="back"><a href={routeHash({ page: 'family' })}><span aria-hidden="true">← </span>{words[language].back}</a></p>
    {section === 'profile' && <MyProfile language={language} csrfToken={csrfToken} />}
    {section === 'children' && <MyDependents language={language} csrfToken={csrfToken} />}
    {section === 'absences' && <Absences language={language} csrfToken={csrfToken} self={self} onChange={() => undefined} />}
    {section === 'fees' && <><MyFees language={language} /><MyHelperDutyCard language={language} /></>}
    {section === 'calendar' && <CalendarFeed language={language} csrfToken={csrfToken} />}
    {section === 'notifications' && <NotificationSettings language={language} csrfToken={csrfToken} />}
  </>
}
