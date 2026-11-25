import { useEffect, useState } from 'react'
import type { Language } from './language'
import { currentSubscription, disablePush, enablePush, pushSupported, readPushStatus, type PushStatus } from './push'

const words = {
  de: { title: 'Push-Mitteilungen auf diesem Gerät', hint: 'Die gleichen Mitteilungen wie per E-Mail, mit denselben Schaltern. Am besten mit der App auf dem Startbildschirm.', on: 'Auf diesem Gerät einschalten', off: 'Auf diesem Gerät ausschalten', active: 'Auf diesem Gerät eingeschaltet.', blocked: 'Mitteilungen sind im Browser blockiert. Bitte in den Einstellungen des Browsers erlauben.', unsupported: 'Dieser Browser unterstützt keine Push-Mitteilungen (auf dem iPhone: die App zuerst zum Home-Bildschirm hinzufügen).', failed: 'Nicht möglich. Bitte später nochmals versuchen.' },
  en: { title: 'Push notifications on this device', hint: 'The same notifications as by email, with the same switches. Best with the app on your home screen.', on: 'Turn on for this device', off: 'Turn off for this device', active: 'On for this device.', blocked: 'Notifications are blocked in the browser. Please allow them in the browser settings.', unsupported: 'This browser does not support push notifications (on an iPhone: add the app to the home screen first).', failed: 'Not possible. Please try again later.' },
  fr: { title: 'Notifications push sur cet appareil', hint: 'Les mêmes notifications que par e-mail, avec les mêmes réglages. Idéal avec l’app sur l’écran d’accueil.', on: 'Activer sur cet appareil', off: 'Désactiver sur cet appareil', active: 'Activées sur cet appareil.', blocked: 'Les notifications sont bloquées dans le navigateur. Veuillez les autoriser dans ses réglages.', unsupported: 'Ce navigateur ne gère pas les notifications push (sur iPhone : ajoutez d’abord l’app à l’écran d’accueil).', failed: 'Impossible. Veuillez réessayer plus tard.' },
  it: { title: 'Notifiche push su questo dispositivo', hint: 'Le stesse notifiche dell’email, con gli stessi interruttori. Ideale con l’app sulla schermata Home.', on: 'Attiva su questo dispositivo', off: 'Disattiva su questo dispositivo', active: 'Attive su questo dispositivo.', blocked: 'Le notifiche sono bloccate nel browser. Consentitele nelle impostazioni del browser.', unsupported: 'Questo browser non supporta le notifiche push (su iPhone: aggiungete prima l’app alla schermata Home).', failed: 'Non possibile. Riprovate più tardi.' },
} satisfies Record<Language, Record<string, string>>

/** Family → notifications: turn push on or off for this browser; hidden when the club has no push. */
export function PushSettings({ language, csrfToken }: { language: Language; csrfToken: string }) {
  const t = words[language]
  const [status, setStatus] = useState<PushStatus | null>(null)
  const [here, setHere] = useState(false)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  useEffect(() => {
    let active = true
    readPushStatus().then(async result => {
      if (!active || result === 'denied') return
      setStatus(result)
      const subscription = await currentSubscription().catch(() => null)
      if (active) setHere(subscription !== null)
    }).catch(() => {})
    return () => { active = false }
  }, [])
  if (!status?.enabled || !status.publicKey) return null
  const key = status.publicKey
  async function turnOn() {
    setBusy(true); setNotice(null)
    try {
      const outcome = await enablePush(key, csrfToken)
      if (outcome === 'ok') setHere(true)
      else setNotice(outcome === 'blocked' ? t.blocked : t.failed)
    } catch { setNotice(t.failed) } finally { setBusy(false) }
  }
  async function turnOff() {
    setBusy(true); setNotice(null)
    try { await disablePush(csrfToken); setHere(false) } catch { setNotice(t.failed) } finally { setBusy(false) }
  }
  return <section aria-labelledby="push-settings" className="card pad stack">
    <h2 id="push-settings">{t.title}</h2>
    <p className="muted small">{t.hint}</p>
    {!pushSupported() ? <p className="muted">{t.unsupported}</p> : here
      ? <><p role="status">{t.active}</p><button type="button" className="btn" disabled={busy} onClick={() => { void turnOff() }}>{t.off}</button></>
      : <button type="button" className="btn primary" disabled={busy} onClick={() => { void turnOn() }}>{t.on}</button>}
    {notice && <p role="alert">{notice}</p>}
  </section>
}
