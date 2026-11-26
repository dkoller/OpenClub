import { Fragment, useEffect, useState, type FormEvent } from 'react'
import { formatDate } from './dates'
import type { Language } from './language'
import {
  deleteAnnouncement, postAnnouncement, readAnnouncements, replaceAnnouncement,
  type Announcement, type AnnouncementOutcome, type Audience, type Draft,
} from './announcements'

const words = {
  de: { title: 'Mitteilungen', empty: 'Keine aktuellen Mitteilungen.', club: 'Ganzer Verein', audience: 'Für', heading: 'Titel', body: 'Text', until: 'Anzeigen bis (leer: 60 Tage)', post: 'Veröffentlichen', warning: 'Keine privaten Angaben wie Gesundheit oder Adressen in Mitteilungen schreiben.', edit: 'Bearbeiten', save: 'Speichern', cancel: 'Abbrechen', remove: 'Löschen', confirmRemove: 'Löschen bestätigen', shownUntil: 'sichtbar bis', invalid: 'Bitte Titel und Text prüfen; das Datum höchstens ein Jahr voraus.', denied: 'Keine Berechtigung.', gone: 'Diese Mitteilung existiert nicht mehr.', failed: 'Aktion fehlgeschlagen.', error: 'Mitteilungen nicht verfügbar.', posted: 'Veröffentlicht.' },
  en: { title: 'Announcements', empty: 'No current announcements.', club: 'Whole club', audience: 'For', heading: 'Title', body: 'Text', until: 'Show until (empty: 60 days)', post: 'Post', warning: 'Do not put private details such as health or addresses into announcements.', edit: 'Edit', save: 'Save', cancel: 'Cancel', remove: 'Delete', confirmRemove: 'Confirm delete', shownUntil: 'shown until', invalid: 'Please check the title and text; the date at most a year ahead.', denied: 'No permission.', gone: 'This announcement no longer exists.', failed: 'Action failed.', error: 'Announcements unavailable.', posted: 'Posted.' },
  fr: { title: 'Annonces', empty: 'Aucune annonce actuelle.', club: 'Tout le club', audience: 'Pour', heading: 'Titre', body: 'Texte', until: 'Afficher jusqu’au (vide : 60 jours)', post: 'Publier', warning: 'Ne mettez pas d’informations privées comme la santé ou des adresses dans les annonces.', edit: 'Modifier', save: 'Enregistrer', cancel: 'Annuler', remove: 'Supprimer', confirmRemove: 'Confirmer la suppression', shownUntil: 'visible jusqu’au', invalid: 'Vérifiez le titre et le texte ; la date au plus un an à l’avance.', denied: 'Pas d’autorisation.', gone: 'Cette annonce n’existe plus.', failed: 'L’action a échoué.', error: 'Annonces indisponibles.', posted: 'Publiée.' },
  it: { title: 'Comunicazioni', empty: 'Nessuna comunicazione attuale.', club: 'Tutto il club', audience: 'Per', heading: 'Titolo', body: 'Testo', until: 'Mostra fino al (vuoto: 60 giorni)', post: 'Pubblica', warning: 'Non inserire dati privati come salute o indirizzi nelle comunicazioni.', edit: 'Modifica', save: 'Salva', cancel: 'Annulla', remove: 'Elimina', confirmRemove: 'Conferma eliminazione', shownUntil: 'visibile fino al', invalid: 'Controlla titolo e testo; la data al massimo un anno avanti.', denied: 'Nessuna autorizzazione.', gone: 'Questa comunicazione non esiste più.', failed: 'Azione non riuscita.', error: 'Comunicazioni non disponibili.', posted: 'Pubblicata.' },
} satisfies Record<Language, Record<string, string>>

const empty: Draft = { title: '', body: '', showUntil: '' }

/** Plain text with its line breaks, never interpreted as HTML. */
function Lines({ text }: { text: string }) {
  return <p>{text.split('\n').map((line, index) => <Fragment key={index}>{index > 0 && <br />}{line}</Fragment>)}</p>
}

function Fields({ draft, setDraft, t }: { draft: Draft; setDraft: (draft: Draft) => void; t: typeof words.en }) {
  return <>
    <label>{t.heading}<input value={draft.title} maxLength={200} required onChange={event => setDraft({ ...draft, title: event.target.value })} /></label>
    <label>{t.body}<textarea value={draft.body} maxLength={5000} required rows={5} onChange={event => setDraft({ ...draft, body: event.target.value })} /></label>
    <label>{t.until}<input type="date" value={draft.showUntil} onChange={event => setDraft({ ...draft, showUntil: event.target.value })} /></label>
  </>
}

/** Announcements for the signed-in member (OpenAPI 1.14.0); the post form only offers the audiences they may use. */
export function Announcements({ language, csrfToken }: { language: Language; csrfToken: string }) {
  const t = words[language]
  const [items, setItems] = useState<Announcement[] | null>(null)
  const [audiences, setAudiences] = useState<Audience[]>([])
  const [state, setState] = useState<'loading' | 'ready' | 'denied' | 'error'>('loading')
  const [revision, setRevision] = useState(0)
  const [audience, setAudience] = useState('')
  const [draft, setDraft] = useState<Draft>(empty)
  const [editing, setEditing] = useState<{ id: string; draft: Draft } | null>(null)
  const [deleting, setDeleting] = useState<string | null>(null)
  const [notice, setNotice] = useState<'invalid' | 'denied' | 'gone' | 'failed' | 'posted' | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let active = true
    readAnnouncements().then(result => {
      if (!active) return
      if (result === 'denied') { setState('denied'); return }
      setItems(result.items); setAudiences(result.audiences); setState('ready')
    }).catch(() => { if (active) setState('error') })
    return () => { active = false }
  }, [revision])

  async function run(action: () => Promise<AnnouncementOutcome>, success?: 'posted') {
    setBusy(true); setNotice(null)
    try {
      const outcome = await action()
      if (outcome === 'ok') { setNotice(success ?? null); setRevision(value => value + 1); return true }
      setNotice(outcome === 'not-found' ? 'gone' : outcome)
      if (outcome !== 'invalid') setRevision(value => value + 1)
    } catch { setNotice('failed') } finally { setBusy(false) }
    return false
  }

  const selected = audiences.find(item => (item.teamId ?? '') === audience) ?? audiences[0]
  async function post(event: FormEvent) {
    event.preventDefault()
    if (!selected) return
    if (await run(() => postAnnouncement(selected.teamId, draft, csrfToken), 'posted')) setDraft(empty)
  }

  if (state === 'denied') return null
  return (
    <section aria-label={t.title}>
      <h2>{t.title}</h2>
      {state === 'loading' && <p role="status">…</p>}
      {state === 'error' && <p role="alert">{t.error}</p>}
      {state === 'ready' && items?.length === 0 && <p>{t.empty}</p>}
      {state === 'ready' && items && items.length > 0 && <ul>{items.map(item => <li key={item.id}>
        {editing?.id === item.id ? <form onSubmit={event => {
          event.preventDefault()
          void run(() => replaceAnnouncement(item.id, editing.draft, csrfToken)).then(done => { if (done) setEditing(null) })
        }}>
          <Fields draft={editing.draft} setDraft={next => setEditing({ id: item.id, draft: next })} t={t} />
          <button type="submit" disabled={busy}>{t.save}</button>
          <button type="button" onClick={() => setEditing(null)}>{t.cancel}</button>
        </form> : <article aria-label={item.title}>
          <h3>{item.title}</h3>
          <p className="muted small">{item.teamName ?? t.club} · {formatDate(item.createdAt)}{item.canManage && <> · {t.shownUntil} {formatDate(item.showUntil)}</>}</p>
          <Lines text={item.body} />
          {item.canManage && <>
            <button type="button" disabled={busy} onClick={() => setEditing({ id: item.id, draft: { title: item.title, body: item.body, showUntil: item.showUntil } })}>{t.edit}</button>
            {deleting === item.id
              ? <button type="button" disabled={busy} onClick={() => { setDeleting(null); void run(() => deleteAnnouncement(item.id, csrfToken)) }}>{t.confirmRemove}</button>
              : <button type="button" disabled={busy} onClick={() => setDeleting(item.id)}>{t.remove}</button>}
          </>}
        </article>}
      </li>)}</ul>}
      {audiences.length > 0 && <form onSubmit={event => { void post(event) }}>
        <label>{t.audience}<select value={selected?.teamId ?? ''} onChange={event => setAudience(event.target.value)}>
          {audiences.map(item => <option key={item.teamId ?? 'club'} value={item.teamId ?? ''}>{item.teamName ?? t.club}</option>)}
        </select></label>
        <Fields draft={draft} setDraft={setDraft} t={t} />
        <p>{t.warning}</p>
        <button type="submit" disabled={busy}>{t.post}</button>
      </form>}
      {notice && <p role={notice === 'posted' ? 'status' : 'alert'}>{t[notice]}</p>}
    </section>
  )
}
