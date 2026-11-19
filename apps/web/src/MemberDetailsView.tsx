import { useEffect, useState } from 'react'
import type { Language } from './language'
import { fetchMemberDetails, type MemberDetails } from './memberDetails'

const words = {
  de: { title: 'Details', close: 'Schliessen', loading: 'Details werden geladen…', denied: 'Keine Berechtigung, diese Details zu sehen.', missing: 'Mitglied nicht gefunden.', error: 'Details nicht verfügbar.', notSet: 'nicht angegeben', yes: 'Ja', no: 'Nein', dateOfBirth: 'Geburtsdatum', email: 'E-Mail', phone: 'Telefon', gender: 'Geschlecht', isPlayer: 'Spieler', archivedAt: 'Archiviert am', hasLinkedLogin: 'Anmeldung verknüpft', invitation: 'Einladung', open: 'offen bis', none: 'keine' },
  en: { title: 'Details', close: 'Close', loading: 'Loading details…', denied: 'No permission to see these details.', missing: 'Member not found.', error: 'Details unavailable.', notSet: 'not set', yes: 'Yes', no: 'No', dateOfBirth: 'Date of birth', email: 'Email', phone: 'Phone', gender: 'Gender', isPlayer: 'Player', archivedAt: 'Archived on', hasLinkedLogin: 'Sign-in linked', invitation: 'Invitation', open: 'open until', none: 'none' },
  fr: { title: 'Détails', close: 'Fermer', loading: 'Chargement des détails…', denied: 'Pas d’autorisation pour voir ces détails.', missing: 'Membre introuvable.', error: 'Détails indisponibles.', notSet: 'non renseigné', yes: 'Oui', no: 'Non', dateOfBirth: 'Date de naissance', email: 'E-mail', phone: 'Téléphone', gender: 'Genre', isPlayer: 'Joueur', archivedAt: 'Archivé le', hasLinkedLogin: 'Connexion liée', invitation: 'Invitation', open: 'ouverte jusqu’au', none: 'aucune' },
  it: { title: 'Dettagli', close: 'Chiudi', loading: 'Caricamento dettagli…', denied: 'Nessun permesso per vedere questi dettagli.', missing: 'Membro non trovato.', error: 'Dettagli non disponibili.', notSet: 'non indicato', yes: 'Sì', no: 'No', dateOfBirth: 'Data di nascita', email: 'Email', phone: 'Telefono', gender: 'Genere', isPlayer: 'Giocatore', archivedAt: 'Archiviato il', hasLinkedLogin: 'Accesso collegato', invitation: 'Invito', open: 'aperto fino al', none: 'nessuno' },
} satisfies Record<Language, Record<string, string>>

type State = MemberDetails | 'loading' | 'denied' | 'not-found' | 'error'

/**
 * Shows exactly the fields the server returned for one member. The server decides what the caller may read;
 * an absent field is not shown at all, and a readable empty one says "not set".
 */
export function MemberDetailsView({ memberId, language, onClose }: {
  memberId: string; language: Language; onClose: () => void
}) {
  const t = words[language]
  const [state, setState] = useState<State>('loading')

  useEffect(() => {
    const controller = new AbortController()
    setState('loading')
    fetchMemberDetails(memberId, controller.signal)
      .then(result => { if (!controller.signal.aborted) setState(result) })
      .catch(() => { if (!controller.signal.aborted) setState('error') })
    return () => controller.abort()
  }, [memberId])

  const text = (value: string | null | undefined) => value ?? t.notSet
  const flag = (value: boolean) => value ? t.yes : t.no
  const date = (value: string | null | undefined) => value ? new Date(value).toLocaleDateString(language) : t.notSet

  let body
  if (state === 'loading') body = <p role="status">{t.loading}</p>
  else if (state === 'denied') body = <p role="alert">{t.denied}</p>
  else if (state === 'not-found') body = <p role="alert">{t.missing}</p>
  else if (state === 'error') body = <p role="alert">{t.error}</p>
  else {
    const rows: [string, string][] = []
    if ('dateOfBirth' in state) rows.push([t.dateOfBirth, text(state.dateOfBirth)])
    if ('email' in state) rows.push([t.email, text(state.email)])
    if ('phone' in state) rows.push([t.phone, text(state.phone)])
    if ('gender' in state) rows.push([t.gender, text(state.gender)])
    if (state.isPlayer !== undefined) rows.push([t.isPlayer, flag(state.isPlayer)])
    if (state.hasLinkedLogin !== undefined) rows.push([t.hasLinkedLogin, flag(state.hasLinkedLogin)])
    if (state.invitation) rows.push([t.invitation, state.invitation.status === 'OPEN'
      ? `${t.open} ${state.invitation.expiresAt ? new Date(state.invitation.expiresAt).toLocaleString(language) : ''}`.trim()
      : t.none])
    if ('archivedAt' in state && state.archivedAt) rows.push([t.archivedAt, date(state.archivedAt)])
    body = <dl>{rows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
  }

  const name = typeof state === 'object' ? `${state.firstName} ${state.lastName}` : ''
  return (
    <section aria-label={name ? `${t.title}: ${name}` : t.title}>
      <h3>{name ? `${t.title}: ${name}` : t.title}</h3>
      {body}
      <button type="button" onClick={onClose}>{t.close}</button>
    </section>
  )
}
