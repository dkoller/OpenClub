import { useState, type FormEvent } from 'react'
import type { Language } from './language'
import { acceptInvitation, secretFromHash, type AcceptOutcome } from './invitations'

const words = {
  de: { title: 'Einladung annehmen', secret: 'Einladungscode oder -link', accept: 'Einladung annehmen', invalid: 'Die Einladung ist ungültig, abgelaufen oder bereits verwendet.', limited: 'Zu viele Versuche. Bitte später erneut versuchen.', unavailable: 'Dienst momentan nicht verfügbar.' },
  en: { title: 'Accept an invitation', secret: 'Invitation code or link', accept: 'Accept invitation', invalid: 'The invitation is invalid, expired or already used.', limited: 'Too many attempts. Please try again later.', unavailable: 'Service temporarily unavailable.' },
  fr: { title: 'Accepter une invitation', secret: 'Code ou lien d’invitation', accept: 'Accepter l’invitation', invalid: 'L’invitation est invalide, expirée ou déjà utilisée.', limited: 'Trop de tentatives. Veuillez réessayer plus tard.', unavailable: 'Service temporairement indisponible.' },
  it: { title: 'Accetta un invito', secret: 'Codice o link di invito', accept: 'Accetta invito', invalid: 'L’invito non è valido, è scaduto o è già stato usato.', limited: 'Troppi tentativi. Riprova più tardi.', unavailable: 'Servizio temporaneamente non disponibile.' },
} satisfies Record<Language, Record<string, string>>

/** Pulls the code out of a pasted link or code; anything that is not a well formed secret is ignored. */
export function pastedSecret(text: string): string | null {
  const trimmed = text.trim()
  const hash = trimmed.indexOf('#')
  return secretFromHash(hash >= 0 ? trimmed.slice(hash) : '#invite=' + trimmed)
}

/** Shown to a signed-in account that is not yet a member. Reloads access after a successful acceptance. */
export function AcceptInvitation({ language, csrfToken, initial, onAccepted }: {
  language: Language; csrfToken: string; initial: string; onAccepted: () => void
}) {
  const t = words[language]
  const [text, setText] = useState(initial)
  const [outcome, setOutcome] = useState<Exclude<AcceptOutcome, 'ok'> | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const secret = pastedSecret(text)
    if (!secret) { setOutcome('invalid'); return }
    setBusy(true)
    setOutcome(null)
    try {
      const result = await acceptInvitation(secret, csrfToken)
      if (result === 'ok') { setText(''); onAccepted(); return }
      setOutcome(result)
    } catch { setOutcome('unavailable') }
    finally { setBusy(false) }
  }

  return (
    <section aria-label={t.title}>
      <h2>{t.title}</h2>
      <form onSubmit={event => { void submit(event) }}>
        <label>{t.secret}
          <input size={60} autoComplete="off" spellCheck={false} maxLength={300} value={text}
            onChange={event => setText(event.target.value)} />
        </label>
        <button type="submit" disabled={busy || text.trim() === ''}>{t.accept}</button>
      </form>
      {outcome && <p role="alert">{outcome === 'invalid' ? t.invalid : outcome === 'rate-limited' ? t.limited : t.unavailable}</p>}
    </section>
  )
}
