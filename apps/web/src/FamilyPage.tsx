import type { Language } from './language'
import { MyProfile } from './MyProfile'
import { MyDependents } from './MyDependents'
import { MyFees } from './MyFees'
import { Absences } from './Absences'
import { CalendarFeed } from './CalendarFeed'

const titles = { de: 'Familie', en: 'Family', fr: 'Famille', it: 'Famiglia' } satisfies Record<Language, string>

/** Me and my children: profile and contacts, children, fees, absences, calendar link and email settings. */
export function FamilyPage({ language, csrfToken, self }: { language: Language; csrfToken: string; self: string | null }) {
  return <>
    <h1>{titles[language]}</h1>
    <MyProfile language={language} csrfToken={csrfToken} />
    <MyDependents language={language} csrfToken={csrfToken} />
    <Absences language={language} csrfToken={csrfToken} self={self} onChange={() => undefined} />
    <MyFees language={language} />
    <CalendarFeed language={language} csrfToken={csrfToken} />
  </>
}
