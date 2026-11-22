import { useEffect, useState } from 'react'
import type { Language } from './language'
import { readNotifications, saveNotifications, type NotificationLanguage, type NotificationStatus } from './notifications'

const words = {
  de: { title: 'E-Mail-Benachrichtigungen', eventChanges: 'Termin geändert oder abgesagt', newEvents: 'Neue Termine', announcements: 'Mitteilungen', shiftReminders: 'Erinnerung am Vortag eines Helfereinsatzes', helpersWanted: 'Helfer gesucht: 7 Tage vor einem Einsatz mit freien Plätzen', language: 'Sprache der E-Mails', save: 'Benachrichtigungen speichern', saved: 'Gespeichert.', off: 'Dieser Verein versendet keine E-Mails.', noEmail: 'Ohne E-Mail-Adresse erhalten Sie keine E-Mails.', guardians: 'Als Erziehungsberechtigte erhalten Sie diese auch für die Teams Ihrer Kinder.', error: 'Nicht verfügbar.' },
  en: { title: 'Email notifications', eventChanges: 'Event changed or cancelled', newEvents: 'New events', announcements: 'Announcements', shiftReminders: 'Reminder the day before a volunteer shift', helpersWanted: 'Helpers wanted: 7 days before a shift with free places', language: 'Email language', save: 'Save notifications', saved: 'Saved.', off: 'This club does not send emails.', noEmail: 'Without an email address you receive no emails.', guardians: 'As a guardian you also receive them for your children’s teams.', error: 'Unavailable.' },
  fr: { title: 'Notifications par e-mail', eventChanges: 'Événement modifié ou annulé', newEvents: 'Nouveaux événements', announcements: 'Communications', shiftReminders: 'Rappel la veille d’un créneau de bénévolat', helpersWanted: 'Bénévoles recherchés : 7 jours avant une tâche avec des places libres', language: 'Langue des e-mails', save: 'Enregistrer les notifications', saved: 'Enregistré.', off: 'Ce club n’envoie pas d’e-mails.', noEmail: 'Sans adresse e-mail, vous ne recevez pas d’e-mails.', guardians: 'En tant que représentant légal, vous les recevez aussi pour les équipes de vos enfants.', error: 'Indisponible.' },
  it: { title: 'Notifiche via email', eventChanges: 'Evento modificato o annullato', newEvents: 'Nuovi eventi', announcements: 'Comunicazioni', shiftReminders: 'Promemoria il giorno prima di un turno di volontariato', helpersWanted: 'Cercasi aiutanti: 7 giorni prima di un turno con posti liberi', language: 'Lingua delle email', save: 'Salva notifiche', saved: 'Salvato.', off: 'Questo club non invia email.', noEmail: 'Senza indirizzo email non ricevi email.', guardians: 'Come tutore le ricevi anche per le squadre dei tuoi figli.', error: 'Non disponibile.' },
} satisfies Record<Language, Record<string, string>>

const names: Record<NotificationLanguage, string> = { de: 'Deutsch', en: 'English', fr: 'Français', it: 'Italiano' }
const switches = ['eventChanges', 'newEvents', 'announcements', 'shiftReminders', 'helpersWanted'] as const

/** The member's own email notification switches and language (OpenAPI 1.19.0), on its own page #/family/notifications. */
export function NotificationSettings({ language, csrfToken }: { language: Language; csrfToken: string }) {
  const t = words[language]
  const [settings, setSettings] = useState<NotificationStatus | 'denied' | 'error' | null>(null)
  const [notice, setNotice] = useState<'saved' | 'error' | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let active = true
    readNotifications().then(result => { if (active) setSettings(result) }, () => { if (active) setSettings('error') })
    return () => { active = false }
  }, [])

  async function save(next: NotificationStatus) {
    setBusy(true); setNotice(null)
    try {
      const outcome = await saveNotifications(next, csrfToken)
      if (outcome === 'ok') { setSettings(next); setNotice('saved') } else if (outcome === 'denied') setSettings('denied')
      else setNotice('error')
    } catch { setNotice('error') } finally { setBusy(false) }
  }

  if (settings === null || settings === 'denied') return null
  if (settings === 'error') return <section aria-label={t.title}><h1>{t.title}</h1><p role="alert">{t.error}</p></section>
  const current = settings
  return (
    <section aria-label={t.title}>
      <h1>{t.title}</h1>
      {!current.emailEnabled && <p>{t.off}</p>}
      {current.emailEnabled && !current.hasEmail && <p>{t.noEmail}</p>}
      <fieldset disabled={busy || !current.emailEnabled}>
        <legend>{t.title}</legend>
        {switches.map(key => <label key={key}><input type="checkbox" checked={current[key]}
          onChange={event => { void save({ ...current, [key]: event.target.checked }) }} /> {t[key]}</label>)}
        <label>{t.language}<select value={current.language}
          onChange={event => { void save({ ...current, language: event.target.value as NotificationLanguage }) }}>
          {(Object.keys(names) as NotificationLanguage[]).map(code => <option key={code} value={code}>{names[code]}</option>)}
        </select></label>
      </fieldset>
      <p>{t.guardians}</p>
      {notice && <p role={notice === 'error' ? 'alert' : 'status'}>{t[notice]}</p>}
    </section>
  )
}
