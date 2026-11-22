import { useEffect, useState } from 'react'
import type { Language } from './language'
import { formatFrancs, readMyFees, type MyFee } from './fees'
import { fetchMyProfile } from './myProfile'

const words = {
  de: { title: 'Meine Beiträge', due: 'fällig am', paid: 'bezahlt am', open: 'offen', for: 'für', error: 'Beiträge nicht verfügbar.', none: 'Keine Beiträge.' },
  en: { title: 'My fees', due: 'due', paid: 'paid on', open: 'open', for: 'for', error: 'Fees unavailable.', none: 'No fees.' },
  fr: { title: 'Mes cotisations', due: 'échéance', paid: 'payée le', open: 'ouverte', for: 'pour', error: 'Cotisations indisponibles.', none: 'Aucune cotisation.' },
  it: { title: 'Le mie quote', due: 'scadenza', paid: 'pagata il', open: 'aperta', for: 'per', error: 'Quote non disponibili.', none: 'Nessuna quota.' },
} satisfies Record<Language, Record<string, string>>

/** The signed-in member's own fees and their children's (OpenAPI 1.12.0), on its own page #/family/fees. */
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

  if (error) return <section aria-label={t.title}><h1>{t.title}</h1><p role="alert">{t.error}</p></section>
  if (!fees) return null
  if (fees.length === 0) return <section aria-label={t.title}><h1>{t.title}</h1><p className="muted">{t.none}</p></section>
  return (
    <section aria-label={t.title}>
      <h1>{t.title}</h1>
      <ul>{fees.map(fee => <li key={fee.periodId + fee.for.id}>
        <strong>{fee.periodName}</strong> · {formatFrancs(fee.amountCents, language)} · {t.due} {fee.dueDate} ·{' '}
        {fee.paidOn ? `${t.paid} ${fee.paidOn}` : <em>{t.open}</em>}
        {fee.for.id !== self && <> · {t.for} {fee.for.firstName}</>}
      </li>)}</ul>
    </section>
  )
}
