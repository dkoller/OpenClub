import { useEffect, useState } from 'react'
import { formatDate } from './dates'
import type { Language } from './language'
import { formatFrancs, qrBillPath, readMyFees, type MyFee } from './fees'
import { fetchMyProfile } from './myProfile'

const words = {
  de: { qrBill: 'QR-Rechnung (PDF)', title: 'Meine Beiträge', due: 'fällig am', paid: 'bezahlt am', open: 'offen', for: 'für', error: 'Beiträge nicht verfügbar.', none: 'Keine Beiträge.' },
  en: { qrBill: 'QR bill (PDF)', title: 'My fees', due: 'due', paid: 'paid on', open: 'open', for: 'for', error: 'Fees unavailable.', none: 'No fees.' },
  fr: { qrBill: 'QR-facture (PDF)', title: 'Mes cotisations', due: 'échéance', paid: 'payée le', open: 'ouverte', for: 'pour', error: 'Cotisations indisponibles.', none: 'Aucune cotisation.' },
  it: { qrBill: 'Fattura QR (PDF)', title: 'Le mie quote', due: 'scadenza', paid: 'pagata il', open: 'aperta', for: 'per', error: 'Quote non disponibili.', none: 'Nessuna quota.' },
} satisfies Record<Language, Record<string, string>>

/**
 * The signed-in member's own fees and their children's (OpenAPI 1.12.0), on its own page #/family/fees, with a QR bill
 * (A4 PDF) to download for each open fee once the club has entered its payment details (1.26.0).
 */
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
        <strong>{fee.periodName}</strong> · {formatFrancs(fee.amountCents, language)} · {t.due} {formatDate(fee.dueDate)} ·{' '}
        {fee.paidOn ? `${t.paid} ${formatDate(fee.paidOn)}` : <em>{t.open}</em>}
        {fee.for.id !== self && <> · {t.for} {fee.for.firstName}</>}
        {fee.qrBill && <> · <a href={qrBillPath(fee.periodId, fee.for.id, language)} download
          aria-label={`${t.qrBill}: ${fee.periodName}${fee.for.id !== self ? `, ${fee.for.firstName}` : ''}`}>{t.qrBill}</a></>}
      </li>)}</ul>
    </section>
  )
}
