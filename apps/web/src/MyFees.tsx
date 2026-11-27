import { useEffect, useState } from 'react'
import { formatDate } from './dates'
import type { Language } from './language'
import { formatFrancs, qrBillPath, readMyFees, type MyFee } from './fees'
import { fetchMyProfile } from './myProfile'

const words = {
  de: { forYou: 'Für dich', forName: 'Für {name}', paidTag: 'Bezahlt', openTag: 'Offen', pay: 'Mit QR-Rechnung bezahlen (PDF)', noBill: 'Der Verein hat noch keine Zahlungsangaben erfasst; die QR-Rechnung folgt.', dueOn: 'fällig am {date}', paidOn: 'bezahlt am {date}', qrBill: 'QR-Rechnung (PDF)', title: 'Meine Beiträge', due: 'fällig am', paid: 'bezahlt am', open: 'offen', for: 'für', error: 'Beiträge nicht verfügbar.', none: 'Keine Beiträge.' },
  en: { forYou: 'For you', forName: 'For {name}', paidTag: 'Paid', openTag: 'Open', pay: 'Pay with QR bill (PDF)', noBill: 'The club has not set up payment yet; the QR bill will follow.', dueOn: 'due {date}', paidOn: 'paid on {date}', qrBill: 'QR bill (PDF)', title: 'My fees', due: 'due', paid: 'paid on', open: 'open', for: 'for', error: 'Fees unavailable.', none: 'No fees.' },
  fr: { forYou: 'Pour toi', forName: 'Pour {name}', paidTag: 'Payé', openTag: 'Ouvert', pay: 'Payer avec la facture QR (PDF)', noBill: 'Le club n’a pas encore saisi ses coordonnées de paiement ; la facture QR suivra.', dueOn: 'échéance le {date}', paidOn: 'payé le {date}', qrBill: 'QR-facture (PDF)', title: 'Mes cotisations', due: 'échéance', paid: 'payée le', open: 'ouverte', for: 'pour', error: 'Cotisations indisponibles.', none: 'Aucune cotisation.' },
  it: { forYou: 'Per te', forName: 'Per {name}', paidTag: 'Pagato', openTag: 'Aperto', pay: 'Paga con la fattura QR (PDF)', noBill: 'Il club non ha ancora inserito i dati di pagamento; la fattura QR seguirà.', dueOn: 'scadenza il {date}', paidOn: 'pagato il {date}', qrBill: 'Fattura QR (PDF)', title: 'Le mie quote', due: 'scadenza', paid: 'pagata il', open: 'aperta', for: 'per', error: 'Quote non disponibili.', none: 'Nessuna quota.' },
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
      {/* One card per fee: for whom, open or paid in words, and how to pay. */}
      <ul className="plain stack">{fees.map(fee => <li key={fee.periodId + fee.for.id} className="card pad stack fee">
        <p className="fee-head"><b>{fee.for.id === self ? t.forYou : t.forName.replace('{name}', fee.for.firstName)}</b>
          <span className={fee.paidOn ? 'chip-tag' : 'open'}>{fee.paidOn ? t.paidTag : t.openTag}</span></p>
        <p><strong>{fee.periodName}</strong> · {formatFrancs(fee.amountCents, language)}</p>
        <p className="muted">{fee.paidOn ? t.paidOn.replace('{date}', formatDate(fee.paidOn)) : t.dueOn.replace('{date}', formatDate(fee.dueDate))}</p>
        {!fee.paidOn && (fee.qrBill
          ? <a className="btn primary" href={qrBillPath(fee.periodId, fee.for.id, language)} download
            aria-label={`${t.qrBill}: ${fee.periodName}${fee.for.id !== self ? `, ${fee.for.firstName}` : ''}`}>{t.pay}</a>
          : <p className="muted small">{t.noBill}</p>)}
      </li>)}</ul>
    </section>
  )
}
