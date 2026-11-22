import { useEffect, useState, type FormEvent } from 'react'
import type { Language } from './language'
import {
  createPeriod, deletePeriod, formatFrancs, parseFrancs, readFees, readPeriods, removeFee, setFee, today,
  type Fee, type FeeFilter, type FeeOutcome, type FeePeriod,
} from './fees'

const words = {
  de: { title: 'Mitgliederbeiträge', name: 'Bezeichnung', due: 'Fällig am', amount: 'Betrag (CHF)', create: 'Periode erstellen', open: 'Öffnen', close: 'Schliessen', paidOf: 'bezahlt', of: 'von', remove: 'Periode löschen', confirmRemove: 'Endgültig löschen', removeWarning: 'Löscht die Periode mit allen Beiträgen. Zur Bestätigung die Bezeichnung eingeben.', cancel: 'Abbrechen', filter: 'Anzeigen', all: 'Alle', openFilter: 'Offen', paid: 'Bezahlt', excluded: 'Ausgeschlossen', member: 'Mitglied', status: 'Status', paidOn: 'Bezahlt am', unpaid: 'Offen', archived: 'archiviert', markPaid: 'Als bezahlt markieren', markUnpaid: 'Als offen markieren', changeAmount: 'Betrag ändern', save: 'Speichern', exclude: 'Ausschliessen', include: 'Aufnehmen', empty: 'Keine Einträge.', none: 'Noch keine Perioden.', invalid: 'Bitte die Angaben prüfen.', conflict: 'Nicht möglich: Bezeichnung schon vergeben oder Bestätigung falsch.', gone: 'Nicht mehr vorhanden.', failed: 'Aktion fehlgeschlagen.', error: 'Beiträge nicht verfügbar.' },
  en: { title: 'Membership fees', name: 'Name', due: 'Due date', amount: 'Amount (CHF)', create: 'Create period', open: 'Open', close: 'Close', paidOf: 'paid', of: 'of', remove: 'Delete period', confirmRemove: 'Delete permanently', removeWarning: 'Deletes the period with all its fees. Type its name to confirm.', cancel: 'Cancel', filter: 'Show', all: 'All', openFilter: 'Open', paid: 'Paid', excluded: 'Excluded', member: 'Member', status: 'Status', paidOn: 'Paid on', unpaid: 'Open', archived: 'archived', markPaid: 'Mark paid', markUnpaid: 'Mark unpaid', changeAmount: 'Change amount', save: 'Save', exclude: 'Exclude', include: 'Include', empty: 'No entries.', none: 'No periods yet.', invalid: 'Please check the details.', conflict: 'Not possible: the name is taken or the confirmation does not match.', gone: 'No longer exists.', failed: 'Action failed.', error: 'Fees unavailable.' },
  fr: { title: 'Cotisations', name: 'Nom', due: 'Échéance', amount: 'Montant (CHF)', create: 'Créer la période', open: 'Ouvrir', close: 'Fermer', paidOf: 'payées', of: 'sur', remove: 'Supprimer la période', confirmRemove: 'Supprimer définitivement', removeWarning: 'Supprime la période et toutes ses cotisations. Saisissez son nom pour confirmer.', cancel: 'Annuler', filter: 'Afficher', all: 'Toutes', openFilter: 'Ouvertes', paid: 'Payées', excluded: 'Exclus', member: 'Membre', status: 'Statut', paidOn: 'Payée le', unpaid: 'Ouverte', archived: 'archivé', markPaid: 'Marquer payée', markUnpaid: 'Marquer ouverte', changeAmount: 'Modifier le montant', save: 'Enregistrer', exclude: 'Exclure', include: 'Inclure', empty: 'Aucune entrée.', none: 'Aucune période.', invalid: 'Vérifiez les informations.', conflict: 'Impossible : nom déjà utilisé ou confirmation incorrecte.', gone: 'N’existe plus.', failed: 'L’action a échoué.', error: 'Cotisations indisponibles.' },
  it: { title: 'Quote sociali', name: 'Nome', due: 'Scadenza', amount: 'Importo (CHF)', create: 'Crea periodo', open: 'Apri', close: 'Chiudi', paidOf: 'pagate', of: 'su', remove: 'Elimina periodo', confirmRemove: 'Elimina definitivamente', removeWarning: 'Elimina il periodo con tutte le quote. Digita il nome per confermare.', cancel: 'Annulla', filter: 'Mostra', all: 'Tutte', openFilter: 'Aperte', paid: 'Pagate', excluded: 'Esclusi', member: 'Membro', status: 'Stato', paidOn: 'Pagata il', unpaid: 'Aperta', archived: 'archiviato', markPaid: 'Segna pagata', markUnpaid: 'Segna aperta', changeAmount: 'Modifica importo', save: 'Salva', exclude: 'Escludi', include: 'Includi', empty: 'Nessuna voce.', none: 'Nessun periodo.', invalid: 'Controlla i dati.', conflict: 'Non possibile: nome già usato o conferma errata.', gone: 'Non esiste più.', failed: 'Azione non riuscita.', error: 'Quote non disponibili.' },
} satisfies Record<Language, Record<string, string>>

type Notice = 'invalid' | 'conflict' | 'gone' | 'failed'

/**
 * Membership fees for holders of MANAGE_FEES (OpenAPI 1.12.0): periods with totals, each member's fee and payment.
 * Hidden for everyone else. Tracking only: nothing here moves money.
 */
export function FeeAdmin({ language, csrfToken }: { language: Language; csrfToken: string }) {
  const t = words[language]
  const [periods, setPeriods] = useState<FeePeriod[] | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'denied' | 'error'>('loading')
  const [revision, setRevision] = useState(0)
  const [name, setName] = useState('')
  const [due, setDue] = useState('')
  const [amount, setAmount] = useState('')
  const [openId, setOpenId] = useState<string | null>(null)
  const [filter, setFilter] = useState<FeeFilter>('all')
  const [fees, setFees] = useState<Fee[] | null>(null)
  const [editing, setEditing] = useState<{ memberId: string; amount: string } | null>(null)
  const [deleting, setDeleting] = useState<{ id: string; confirmation: string } | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let active = true
    readPeriods().then(result => {
      if (!active) return
      if (result === 'denied') { setState('denied'); return }
      setPeriods(result); setState('ready')
    }).catch(() => { if (active) setState('error') })
    return () => { active = false }
  }, [revision])

  useEffect(() => {
    if (!openId) return
    let active = true
    setFees(null)
    readFees(openId, filter).then(result => {
      if (active) setFees(result === 'denied' ? [] : result)
    }).catch(() => { if (active) setNotice('failed') })
    return () => { active = false }
  }, [openId, filter, revision])

  async function run(action: () => Promise<FeeOutcome>) {
    setBusy(true); setNotice(null)
    try {
      const outcome = await action()
      if (outcome === 'denied') { setState('denied'); return false }
      if (outcome !== 'ok') { setNotice(outcome === 'not-found' ? 'gone' : outcome); return false }
      setRevision(value => value + 1)
      return true
    } catch { setNotice('failed'); return false } finally { setBusy(false) }
  }

  async function create(event: FormEvent) {
    event.preventDefault()
    const cents = parseFrancs(amount)
    if (cents === null) { setNotice('invalid'); return }
    if (await run(() => createPeriod(name, due, cents, csrfToken))) { setName(''); setDue(''); setAmount('') }
  }

  async function saveAmount(event: FormEvent, fee: Fee) {
    event.preventDefault()
    if (!openId || !editing) return
    const cents = parseFrancs(editing.amount)
    if (cents === null) { setNotice('invalid'); return }
    if (await run(() => setFee(openId, fee.memberId, cents, fee.paidOn, csrfToken))) setEditing(null)
  }

  if (state === 'denied') return null
  const period = periods?.find(item => item.id === openId)
  return (
    <section aria-label={t.title}>
      <h2>{t.title}</h2>
      {state === 'loading' && <p role="status">…</p>}
      {state === 'error' && <p role="alert">{t.error}</p>}
      {state === 'ready' && periods?.length === 0 && <p>{t.none}</p>}
      {state === 'ready' && periods && periods.length > 0 && <ul>{periods.map(item => <li key={item.id}>
        <strong>{item.name}</strong> · {t.due} {item.dueDate} · {formatFrancs(item.amountCents, language)} ·{' '}
        {item.paid} {t.of} {item.members} {t.paidOf} ({formatFrancs(item.paidCents, language)} {t.of} {formatFrancs(item.totalCents, language)})
        <button type="button" aria-expanded={openId === item.id} onClick={() => {
          setOpenId(openId === item.id ? null : item.id); setFilter('all'); setEditing(null); setNotice(null)
        }}>{openId === item.id ? t.close : t.open}</button>
        <button type="button" disabled={busy} onClick={() => setDeleting({ id: item.id, confirmation: '' })}>{t.remove}</button>
        {deleting?.id === item.id && <form onSubmit={event => {
          event.preventDefault()
          void run(() => deletePeriod(item.id, deleting.confirmation, csrfToken)).then(done => {
            if (done) { setDeleting(null); if (openId === item.id) setOpenId(null) }
          })
        }}>
          <p>{t.removeWarning}</p>
          <label>{t.name}<input value={deleting.confirmation} autoComplete="off" required
            onChange={event => setDeleting({ id: item.id, confirmation: event.target.value })} /></label>
          <button type="submit" disabled={busy || deleting.confirmation !== item.name}>{t.confirmRemove}</button>
          <button type="button" onClick={() => setDeleting(null)}>{t.cancel}</button>
        </form>}
      </li>)}</ul>}
      {period && <section aria-label={period.name}>
        <h3>{period.name}</h3>
        <label>{t.filter}<select value={filter} onChange={event => setFilter(event.target.value as FeeFilter)}>
          <option value="all">{t.all}</option><option value="open">{t.openFilter}</option>
          <option value="paid">{t.paid}</option><option value="excluded">{t.excluded}</option>
        </select></label>
        {fees === null ? <p role="status">…</p> : fees.length === 0 ? <p>{t.empty}</p>
          : <table className="cards"><thead><tr><th scope="col">{t.member}</th><th scope="col">{t.amount}</th><th scope="col">{t.status}</th><td /></tr></thead>
            <tbody>{fees.map(fee => <tr key={fee.memberId}>
              <th scope="row">{fee.lastName} {fee.firstName}{fee.archived && <> ({t.archived})</>}</th>
              <td data-label={t.amount}>{fee.amountCents === null ? '–' : editing?.memberId === fee.memberId
                ? <form onSubmit={event => { void saveAmount(event, fee) }}>
                  <label>{t.amount}<input inputMode="decimal" value={editing.amount} required
                    onChange={event => setEditing({ memberId: fee.memberId, amount: event.target.value })} /></label>
                  <button type="submit" disabled={busy}>{t.save}</button>
                  <button type="button" onClick={() => setEditing(null)}>{t.cancel}</button>
                </form> : formatFrancs(fee.amountCents, language)}</td>
              <td data-label={t.status}>{fee.amountCents === null ? t.excluded : fee.paidOn ? `${t.paidOn} ${fee.paidOn}` : t.unpaid}</td>
              <td>{fee.amountCents === null
                ? <button type="button" disabled={busy}
                  onClick={() => { void run(() => setFee(period.id, fee.memberId, period.amountCents, null, csrfToken)) }}>{t.include}</button>
                : <>
                  <button type="button" disabled={busy} onClick={() => {
                    void run(() => setFee(period.id, fee.memberId, fee.amountCents ?? 0, fee.paidOn ? null : today(), csrfToken))
                  }}>{fee.paidOn ? t.markUnpaid : t.markPaid}</button>
                  <button type="button" disabled={busy} onClick={() => setEditing({ memberId: fee.memberId, amount: ((fee.amountCents ?? 0) / 100).toFixed(2) })}>{t.changeAmount}</button>
                  {!fee.archived && <button type="button" disabled={busy}
                    onClick={() => { void run(() => removeFee(period.id, fee.memberId, csrfToken)) }}>{t.exclude}</button>}
                </>}</td>
            </tr>)}</tbody></table>}
      </section>}
      <form onSubmit={event => { void create(event) }}>
        <label>{t.name}<input value={name} maxLength={200} required onChange={event => setName(event.target.value)} /></label>
        <label>{t.due}<input type="date" value={due} required onChange={event => setDue(event.target.value)} /></label>
        <label>{t.amount}<input inputMode="decimal" value={amount} required placeholder="150.00" onChange={event => setAmount(event.target.value)} /></label>
        <button type="submit" disabled={busy}>{t.create}</button>
      </form>
      {notice && <p role="alert">{t[notice]}</p>}
    </section>
  )
}
