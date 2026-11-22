import { useEffect, useState } from 'react'
import type { Language } from './language'
import { createFeed, readFeedStatus, revokeFeed, type FeedStatus } from './calendarFeed'

const words = {
  de: { title: 'Kalender-Abo', intro: 'Abonnieren Sie Ihr Programm im Kalender Ihres Telefons oder in Google/Apple Kalender. Der Link enthält Titel, Team, Zeit, Ort und Notizen Ihrer Termine (und der Ihrer Kinder), keine Namen.', none: 'Noch kein Link.', active: 'Link aktiv seit', create: 'Link erstellen', replace: 'Neuen Link erstellen (der alte funktioniert nicht mehr)', revoke: 'Link widerrufen', link: 'Ihr Link (wird nur jetzt angezeigt)', copy: 'Kopieren', copied: 'Kopiert.', warning: 'Wer diesen Link kennt, sieht Ihr Programm. Nicht weitergeben.', revoked: 'Link widerrufen.', error: 'Nicht verfügbar.' },
  en: { title: 'Calendar subscription', intro: 'Subscribe to your schedule in your phone’s calendar or Google/Apple Calendar. The link contains the title, team, time, place and notes of your events (and your children’s), no names.', none: 'No link yet.', active: 'Link active since', create: 'Create link', replace: 'Create a new link (the old one stops working)', revoke: 'Revoke link', link: 'Your link (shown only now)', copy: 'Copy', copied: 'Copied.', warning: 'Anyone with this link sees your schedule. Do not share it.', revoked: 'Link revoked.', error: 'Unavailable.' },
  fr: { title: 'Abonnement au calendrier', intro: 'Abonnez-vous à votre programme dans le calendrier de votre téléphone ou dans Google/Apple Agenda. Le lien contient le titre, l’équipe, l’heure, le lieu et les notes de vos événements (et de ceux de vos enfants), sans noms.', none: 'Pas encore de lien.', active: 'Lien actif depuis', create: 'Créer le lien', replace: 'Créer un nouveau lien (l’ancien ne fonctionne plus)', revoke: 'Révoquer le lien', link: 'Votre lien (affiché seulement maintenant)', copy: 'Copier', copied: 'Copié.', warning: 'Quiconque a ce lien voit votre programme. Ne le partagez pas.', revoked: 'Lien révoqué.', error: 'Indisponible.' },
  it: { title: 'Abbonamento al calendario', intro: 'Abbonati al tuo programma nel calendario del telefono o in Google/Apple Calendar. Il link contiene titolo, squadra, orario, luogo e note dei tuoi eventi (e di quelli dei tuoi figli), senza nomi.', none: 'Nessun link ancora.', active: 'Link attivo dal', create: 'Crea link', replace: 'Crea un nuovo link (quello vecchio non funziona più)', revoke: 'Revoca link', link: 'Il tuo link (mostrato solo ora)', copy: 'Copia', copied: 'Copiato.', warning: 'Chi ha questo link vede il tuo programma. Non condividerlo.', revoked: 'Link revocato.', error: 'Non disponibile.' },
} satisfies Record<Language, Record<string, string>>

/** The member's calendar subscription link (OpenAPI 1.17.0): created, shown once, replaced or revoked. */
export function CalendarFeed({ language, csrfToken }: { language: Language; csrfToken: string }) {
  const t = words[language]
  const [status, setStatus] = useState<FeedStatus | 'denied' | 'error' | null>(null)
  const [link, setLink] = useState<string | null>(null)
  const [notice, setNotice] = useState<'copied' | 'revoked' | 'error' | null>(null)
  const [busy, setBusy] = useState(false)
  const [revision, setRevision] = useState(0)

  useEffect(() => {
    let active = true
    readFeedStatus().then(result => { if (active) setStatus(result) }, () => { if (active) setStatus('error') })
    return () => { active = false }
  }, [revision])

  async function act(action: 'create' | 'revoke') {
    setBusy(true); setNotice(null); setLink(null)
    try {
      if (action === 'create') {
        const result = await createFeed(csrfToken)
        if (result === 'denied') { setStatus('denied'); return }
        setLink(result)
      } else if (await revokeFeed(csrfToken) === 'ok') setNotice('revoked')
    } catch { setNotice('error') } finally { setBusy(false); setRevision(value => value + 1) }
  }

  async function copy() {
    if (!link) return
    try { await navigator.clipboard.writeText(link); setNotice('copied') } catch { setNotice('error') }
  }

  if (status === 'denied' || status === null) return null
  return (
    <section aria-label={t.title}>
      <h1>{t.title}</h1>
      <p>{t.intro}</p>
      {status === 'error' ? <p role="alert">{t.error}</p> : <>
        <p>{status.active && status.createdAt ? `${t.active} ${new Date(status.createdAt).toLocaleDateString(language)}` : t.none}</p>
        {link && <>
          <label>{t.link}<input readOnly value={link} onFocus={event => event.target.select()} /></label>
          <button type="button" onClick={() => { void copy() }}>{t.copy}</button>
          <p>{t.warning}</p>
        </>}
        <button type="button" disabled={busy} onClick={() => { void act('create') }}>{status.active ? t.replace : t.create}</button>
        {status.active && <button type="button" disabled={busy} onClick={() => { void act('revoke') }}>{t.revoke}</button>}
      </>}
      {notice && <p role={notice === 'error' ? 'alert' : 'status'}>{t[notice]}</p>}
    </section>
  )
}
