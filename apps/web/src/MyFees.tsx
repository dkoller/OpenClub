import { useEffect, useState } from 'react'
import type { Language } from './language'
import { formatFrancs, readMyFees, type MyFee } from './fees'
import { fetchMyProfile } from './myProfile'

const words = {
  de: { title: 'Meine Beiträge', due: 'fällig am', paid: 'bezahlt am', open: 'offen', for: 'für', error: 'Beiträge nicht verfügbar.' },
  en: { title: 'My fees', due: 'due', paid: 'paid on', open: 'open', for: 'for', error: 'Fees unavailable.' },
  fr: { title: 'Mes cotisations', due: 'échéance', paid: 'payée le', open: 'ouverte', for: 'pour', error: 'Cotisations indisponibles.' },
  it: { title: 'Le mie quote', due: 'scadenza', paid: 'pagata il', open: 'aperta', for: 'per', error: 'Quote non disponibili.' },
} satisfies Record<Language, Record<string, string>>

/** The signed-in member's own fees and their children's (OpenAPI 1.12.0); hidden when there are none. */
export function MyFees({ language }: { language: Language }) {
  const t = words[language]
  const [fees, setFees] = useState<MyFee[] | null>(null)
  const [self, setSelf] = useState<string | null>(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    let active = true
    Promise.all([readMyFees(), fetchMyProfile().catch(() => 'denied' as const)]).then(([result, profile]) => {
      if (!active) return
      setFees(result === 'denied' ? [] : result)
      setSelf(profile === 'denied' ? null : profile.profile.id)
    }).catch(() => { if (active) setError(true) })
    return () => { active = false }
  }, [])

  if (error) return <section aria-label={t.title}><h2>{t.title}</h2><p role="alert">{t.error}</p></section>
  if (!fees || fees.length === 0) return null
  return (
    <section aria-label={t.title}>
      <h2>{t.title}</h2>
      <ul>{fees.map(fee => <li key={fee.periodId + fee.for.id}>
        <strong>{fee.periodName}</strong> · {formatFrancs(fee.amountCents, language)} · {t.due} {fee.dueDate} ·{' '}
        {fee.paidOn ? `${t.paid} ${fee.paidOn}` : <em>{t.open}</em>}
        {fee.for.id !== self && <> · {t.for} {fee.for.firstName}</>}
      </li>)}</ul>
    </section>
  )
}
