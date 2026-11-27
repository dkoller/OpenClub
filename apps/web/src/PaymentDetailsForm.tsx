import { useEffect, useState, type FormEvent } from 'react'
import { Reveal } from './Reveal'
import type { Language } from './language'
import { readPaymentDetails, savePaymentDetails, type PaymentDetailsInput } from './fees'

const words = {
  de: { account: 'Konto für QR-Rechnungen', change: 'Zahlungsangaben ändern', title: 'Zahlungsangaben für QR-Rechnungen', hint: 'Erscheinen auf jeder QR-Rechnung. Mit einer QR-IBAN trägt die Rechnung eine QR-Referenz, sonst eine Creditor Reference (RF). Die Referenz jedes Mitglieds steht in der Beitragsliste.', none: 'Noch keine Zahlungsangaben: Mitglieder können noch keine QR-Rechnung herunterladen.', iban: 'IBAN oder QR-IBAN', qrIban: 'QR-IBAN erkannt.', holder: 'Kontoinhaber', street: 'Strasse (optional)', number: 'Hausnummer (optional)', postal: 'PLZ', town: 'Ort', country: 'Land', CH: 'Schweiz', LI: 'Liechtenstein', save: 'Zahlungsangaben speichern', saved: 'Gespeichert.', invalid: 'Bitte prüfen: IBAN aus der Schweiz oder Liechtenstein, Kontoinhaber und Strasse bis 70, Hausnummer und PLZ bis 16, Ort bis 35 Zeichen; Hausnummer nur mit Strasse.', failed: 'Speichern fehlgeschlagen.', error: 'Zahlungsangaben nicht verfügbar.' },
  en: { account: 'Account for QR bills', change: 'Change payment details', title: 'Payment details for QR bills', hint: 'Shown on every QR bill. With a QR-IBAN the bill carries a QR reference, otherwise a creditor reference (RF). Each member’s reference is shown in the fee list.', none: 'No payment details yet: members cannot download a QR bill.', iban: 'IBAN or QR-IBAN', qrIban: 'QR-IBAN recognised.', holder: 'Account holder', street: 'Street (optional)', number: 'Building number (optional)', postal: 'Postal code', town: 'Town', country: 'Country', CH: 'Switzerland', LI: 'Liechtenstein', save: 'Save payment details', saved: 'Saved.', invalid: 'Please check: an IBAN from Switzerland or Liechtenstein, account holder and street up to 70, building number and postal code up to 16, town up to 35 characters; a building number only with a street.', failed: 'Saving failed.', error: 'Payment details unavailable.' },
  fr: { account: 'Compte pour les factures QR', change: 'Modifier les coordonnées de paiement', title: 'Coordonnées de paiement pour les QR-factures', hint: 'Figurent sur chaque QR-facture. Avec un QR-IBAN, la facture porte une référence QR, sinon une référence créancier (RF). La référence de chaque membre figure dans la liste des cotisations.', none: 'Pas encore de coordonnées de paiement : les membres ne peuvent pas encore télécharger de QR-facture.', iban: 'IBAN ou QR-IBAN', qrIban: 'QR-IBAN reconnu.', holder: 'Titulaire du compte', street: 'Rue (facultatif)', number: 'Numéro (facultatif)', postal: 'NPA', town: 'Localité', country: 'Pays', CH: 'Suisse', LI: 'Liechtenstein', save: 'Enregistrer les coordonnées', saved: 'Enregistré.', invalid: 'Veuillez vérifier : IBAN de Suisse ou du Liechtenstein, titulaire et rue jusqu’à 70, numéro et NPA jusqu’à 16, localité jusqu’à 35 caractères ; un numéro seulement avec une rue.', failed: 'L’enregistrement a échoué.', error: 'Coordonnées de paiement indisponibles.' },
  it: { account: 'Conto per le fatture QR', change: 'Modifica i dati di pagamento', title: 'Dati di pagamento per le fatture QR', hint: 'Appaiono su ogni fattura QR. Con un QR-IBAN la fattura riporta un riferimento QR, altrimenti un riferimento creditore (RF). Il riferimento di ogni membro è nell’elenco delle quote.', none: 'Ancora nessun dato di pagamento: i membri non possono ancora scaricare una fattura QR.', iban: 'IBAN o QR-IBAN', qrIban: 'QR-IBAN riconosciuto.', holder: 'Titolare del conto', street: 'Via (facoltativo)', number: 'Numero civico (facoltativo)', postal: 'NPA', town: 'Località', country: 'Paese', CH: 'Svizzera', LI: 'Liechtenstein', save: 'Salva i dati di pagamento', saved: 'Salvato.', invalid: 'Controlla: IBAN svizzero o del Liechtenstein, titolare e via fino a 70, numero civico e NPA fino a 16, località fino a 35 caratteri; numero civico solo con la via.', failed: 'Salvataggio non riuscito.', error: 'Dati di pagamento non disponibili.' },
} satisfies Record<Language, Record<string, string>>

const empty: PaymentDetailsInput = { iban: '', holderName: '', street: '', buildingNumber: '', postalCode: '', town: '', country: 'CH' }

/** The club's account and address for QR bills (OpenAPI 1.26.0), for holders of MANAGE_FEES; hidden for others. */
export function PaymentDetailsForm({ language, csrfToken }: { language: Language; csrfToken: string }) {
  const t = words[language]
  const [form, setForm] = useState<PaymentDetailsInput>(empty)
  const [state, setState] = useState<'loading' | 'ready' | 'denied' | 'error'>('loading')
  const [stored, setStored] = useState<'none' | 'normal' | 'qr'>('none')
  const [notice, setNotice] = useState<'saved' | 'invalid' | 'failed' | null>(null)
  const [busy, setBusy] = useState(false)
  const [revision, setRevision] = useState(0)
  const [saved, setSaved] = useState<PaymentDetailsInput | null>(null)

  useEffect(() => {
    let active = true
    readPaymentDetails().then(result => {
      if (!active) return
      if (result === 'denied') { setState('denied'); return }
      if (result === 'none') setStored('none')
      else {
        setStored(result.qrIban ? 'qr' : 'normal')
        const details = { iban: result.iban, holderName: result.holderName, street: result.street ?? '', buildingNumber: result.buildingNumber ?? '',
          postalCode: result.postalCode, town: result.town, country: result.country }
        setForm(details); setSaved(details)
      }
      setState('ready')
    }).catch(() => { if (active) setState('error') })
    return () => { active = false }
  }, [revision])

  async function save(event: FormEvent) {
    event.preventDefault()
    setBusy(true); setNotice(null)
    try {
      const outcome = await savePaymentDetails(form, csrfToken)
      if (outcome === 'denied') { setState('denied'); return }
      if (outcome !== 'ok') { setNotice('invalid'); return }
      setNotice('saved'); setRevision(value => value + 1)
    } catch { setNotice('failed') } finally { setBusy(false) }
  }

  if (state === 'denied') return null
  const field = (key: keyof Omit<PaymentDetailsInput, 'country'>) => ({
    value: form[key] ?? '', onChange: (event: { target: { value: string } }) => setForm({ ...form, [key]: event.target.value }),
  })
  return (
    <section aria-label={t.title} className="card pad stack">
      <h2>{t.title}</h2>
      {state === 'loading' && <p role="status">…</p>}
      {state === 'error' && <p role="alert">{t.error}</p>}
      {state === 'ready' && stored === 'none' && <p className="open-note">{t.none}</p>}
      {/* Saved details as a short summary; the form only opens to change them. */}
      {state === 'ready' && saved && <dl className="summary">
        <dt>{t.account}</dt><dd><span className="nowrap">{saved.iban.replace(/(.{4})/g, '$1 ').trim()}</span>{stored === 'qr' && <> · {t.qrIban}</>}</dd>
        <dt>{t.holder}</dt><dd>{saved.holderName}<br />{[saved.street, saved.buildingNumber].filter(Boolean).join(' ')}{saved.street ? <br /> : null}
          {saved.country}-{saved.postalCode} {saved.town}</dd>
      </dl>}
      {state === 'ready' && <p className="muted small">{t.hint}</p>}
      {state === 'ready' && <Reveal label={t.change} open={stored === 'none'}><form className="form-grid" onSubmit={event => { void save(event) }}>
        <label>{t.iban}<input {...field('iban')} required maxLength={30} autoComplete="off" spellCheck={false} placeholder="CH93 0076 2011 6238 5295 7" /></label>
        <label>{t.holder}<input {...field('holderName')} required maxLength={70} /></label>
        <label>{t.street}<input {...field('street')} maxLength={70} /></label>
        <label>{t.number}<input {...field('buildingNumber')} maxLength={16} /></label>
        <label>{t.postal}<input {...field('postalCode')} required maxLength={16} /></label>
        <label>{t.town}<input {...field('town')} required maxLength={35} /></label>
        <label>{t.country}<select value={form.country} onChange={event => setForm({ ...form, country: event.target.value === 'LI' ? 'LI' : 'CH' })}>
          <option value="CH">{t.CH}</option><option value="LI">{t.LI}</option>
        </select></label>
        <button type="submit" disabled={busy}>{t.save}</button>
      </form></Reveal>}
      {notice && <p role={notice === 'saved' ? 'status' : 'alert'}>{t[notice]}</p>}
    </section>
  )
}
