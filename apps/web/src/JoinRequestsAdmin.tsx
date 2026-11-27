import { useEffect, useState } from 'react'
import { formatDate } from './dates'
import { formatPhone } from './phone'
import type { Language } from './language'
import { acceptJoinRequest, declineJoinRequest, readJoinRequests, readJoinSettings, setJoinOpen, type JoinRequest } from './joinRequests'

const words = {
  de: { title: 'Aufnahmegesuche', switch: 'Öffentliches Aufnahmeformular eingeschaltet', none: 'Keine offenen Gesuche.', child: 'Kind', parent: 'Elternteil', team: 'Team', accept: 'Aufnehmen', decline: 'Ablehnen', confirm: 'Wirklich ablehnen und löschen', accepted: 'Mitglied angelegt. Bei Kindern jetzt den Elternteil als erziehungsberechtigt verknüpfen.', rejected: 'Das Mitglied kann so nicht angelegt werden (z. B. inzwischen volljährig). Bitte von Hand erfassen.', gone: 'Das Gesuch wurde inzwischen erledigt.', failed: 'Nicht gespeichert.', retention: 'Offene Gesuche werden nach 60 Tagen gelöscht.' },
  en: { title: 'Membership applications', switch: 'Public application form is on', none: 'No open applications.', child: 'Child', parent: 'Parent', team: 'Team', accept: 'Accept', decline: 'Decline', confirm: 'Really decline and delete', accepted: 'Member created. For a child, now link the parent as a guardian.', rejected: 'The member cannot be created like this (for example now of age). Please add them by hand.', gone: 'The application was handled meanwhile.', failed: 'Not saved.', retention: 'Open applications are deleted after 60 days.' },
  fr: { title: 'Demandes d’adhésion', switch: 'Formulaire public activé', none: 'Aucune demande ouverte.', child: 'Enfant', parent: 'Parent', team: 'Équipe', accept: 'Accepter', decline: 'Refuser', confirm: 'Vraiment refuser et supprimer', accepted: 'Membre créé. Pour un enfant, liez maintenant le parent comme représentant légal.', rejected: 'Le membre ne peut pas être créé ainsi (p. ex. devenu majeur). Saisissez-le à la main.', gone: 'La demande a été traitée entre-temps.', failed: 'Non enregistré.', retention: 'Les demandes ouvertes sont supprimées après 60 jours.' },
  it: { title: 'Domande di adesione', switch: 'Modulo pubblico attivo', none: 'Nessuna domanda aperta.', child: 'Figlio/a', parent: 'Genitore', team: 'Squadra', accept: 'Accetta', decline: 'Rifiuta', confirm: 'Rifiutare ed eliminare davvero', accepted: 'Membro creato. Per un figlio, collega ora il genitore come tutore.', rejected: 'Il membro non può essere creato così (ad es. ora maggiorenne). Inseriscilo a mano.', gone: 'La domanda è stata gestita nel frattempo.', failed: 'Non salvato.', retention: 'Le domande aperte vengono eliminate dopo 60 giorni.' },
} satisfies Record<Language, Record<string, string>>

/** Editors switch the public form and handle applications (OpenAPI 1.22.0); hidden without EDIT_MEMBERS. */
export function JoinRequestsAdmin({ language, csrfToken, onChanged }: { language: Language; csrfToken: string; onChanged: () => void }) {
  const t = words[language]
  const [open, setOpen] = useState<boolean | 'denied' | null>(null)
  const [items, setItems] = useState<JoinRequest[]>([])
  const [revision, setRevision] = useState(0)
  const [busy, setBusy] = useState(false)
  const [confirming, setConfirming] = useState<string | null>(null)
  const [notice, setNotice] = useState<'accepted' | 'rejected' | 'gone' | 'failed' | null>(null)

  useEffect(() => {
    let active = true
    Promise.all([readJoinSettings(), readJoinRequests()]).then(([settings, list]) => {
      if (!active) return
      setOpen(settings === 'denied' || list === 'denied' ? 'denied' : settings)
      if (list !== 'denied') setItems(list)
    }, () => { if (active) setOpen('denied') })
    return () => { active = false }
  }, [revision])

  async function act(action: () => Promise<string>, success: 'accepted' | null = null) {
    setBusy(true); setNotice(null)
    try {
      const outcome = await action()
      if (outcome === 'ok') { if (success) { setNotice(success); onChanged() } }
      else if (outcome === 'denied') setOpen('denied')
      else setNotice(outcome as 'rejected' | 'gone')
    } catch { setNotice('failed') } finally { setBusy(false); setRevision(value => value + 1) }
  }

  if (open === null || open === 'denied') return null
  return (
    <section aria-label={t.title}>
      <h2 className="vh">{t.title}</h2>
      <label><input type="checkbox" checked={open} disabled={busy}
        onChange={event => { void act(() => setJoinOpen(event.target.checked, csrfToken)) }} /> {t.switch}</label>
      {/* One card per application: who and for which team, how to reach them, their message, then the decision. */}
      {items.length === 0 ? <p>{t.none}</p> : <ul className="plain stack">{items.map(item => <li key={item.id} className="card pad stack application">
        <div>
          <h3>{item.firstName} {item.lastName}</h3>
          <p className="muted">{formatDate(item.dateOfBirth)}{item.kind === 'CHILD' ? ` · ${t.child}` : ''}{item.teamName && <> · {t.team}: {item.teamName}</>}</p>
        </div>
        <p>{item.kind === 'CHILD' && <>{t.parent}: {item.parentFirstName} {item.parentLastName}<br /></>}
          <a href={`mailto:${item.email}`}>{item.email}</a> · <a href={`tel:${item.phone.replace(/\s/g, '')}`}>{formatPhone(item.phone)}</a></p>
        {item.message && <blockquote>{item.message}</blockquote>}
        <div className="actions">
          <button type="button" className="btn primary" disabled={busy} onClick={() => { void act(() => acceptJoinRequest(item.id, csrfToken), 'accepted') }}>{t.accept}</button>
          {confirming === item.id
            ? <button type="button" disabled={busy} onClick={() => { setConfirming(null); void act(() => declineJoinRequest(item.id, csrfToken)) }}>{t.confirm}</button>
            : <button type="button" className="danger-quiet" disabled={busy} onClick={() => setConfirming(item.id)}>{t.decline}</button>}
        </div>
      </li>)}</ul>}
      <p className="muted small">{t.retention}</p>
      {notice && <p role={notice === 'accepted' ? 'status' : 'alert'}>{t[notice]}</p>}
    </section>
  )
}
