import { useState, type FormEvent } from 'react'
import type { Language } from './language'
import { ADULT_AGE_MAX, ADULT_AGE_MIN, parseAdultAge, saveAdultAge } from './policy'

const words = {
  de: { title: 'Vereinsname bearbeiten', label: 'Vereinsname', save: 'Speichern', error: 'Vereinsname konnte nicht gespeichert werden.', policyTitle: 'Spieler-Geschlecht', required: 'Geschlecht für Spieler verlangen', savePolicy: 'Regel speichern', conflict: 'Aktive Spieler ohne Geschlechtsangabe verhindern diese Regel.', policyError: 'Regel konnte nicht gespeichert werden.', ageTitle: 'Volljährigkeit', ageLabel: 'Erwachsen ab Alter (13–25)', saveAge: 'Alter speichern', ageConflict: 'Mitglieder, die dadurch erwachsen würden, haben noch keine E-Mail und Telefonnummer.', ageError: 'Alter konnte nicht gespeichert werden.' },
  en: { title: 'Edit club name', label: 'Club name', save: 'Save', error: 'Could not update club name.', policyTitle: 'Player gender rule', required: 'Require gender for players', savePolicy: 'Save policy', conflict: 'Existing players without gender must be updated first.', policyError: 'Could not save the player policy.', ageTitle: 'Adult age', ageLabel: 'Adult from age (13–25)', saveAge: 'Save adult age', ageConflict: 'Members who would become adults still lack email and phone.', ageError: 'Could not save the adult age.' },
  fr: { title: 'Modifier le nom du club', label: 'Nom du club', save: 'Enregistrer', error: 'Impossible de modifier le nom du club.', policyTitle: 'Règle de genre des joueurs', required: 'Exiger le genre des joueurs', savePolicy: 'Enregistrer la règle', conflict: 'Des joueurs sans genre renseigné empêchent cette règle.', policyError: 'Impossible d’enregistrer la règle.', ageTitle: 'Âge adulte', ageLabel: 'Adulte dès l’âge de (13–25)', saveAge: 'Enregistrer l’âge', ageConflict: 'Des membres qui deviendraient adultes n’ont pas encore d’e-mail et de téléphone.', ageError: 'Impossible d’enregistrer l’âge.' },
  it: { title: 'Modifica nome del club', label: 'Nome del club', save: 'Salva', error: 'Impossibile aggiornare il nome del club.', policyTitle: 'Regola sul genere dei giocatori', required: 'Richiedi il genere per i giocatori', savePolicy: 'Salva regola', conflict: 'Alcuni giocatori non hanno un genere indicato.', policyError: 'Impossibile salvare la regola.', ageTitle: 'Età adulta', ageLabel: 'Adulto dall’età di (13–25)', saveAge: 'Salva età', ageConflict: 'Alcuni membri che diventerebbero adulti non hanno ancora email e telefono.', ageError: 'Impossibile salvare l’età.' },
} satisfies Record<Language, Record<string, string>>

export function ClubSettings({ language, name, csrfToken, onChanged,
  genderRequiredForPlayers, onPolicyChanged, adultAgeYears, onAdultAgeChanged }: {
  language: Language
  name: string
  csrfToken: string
  onChanged: (name: string) => void
  genderRequiredForPlayers: boolean
  onPolicyChanged: (required: boolean) => void
  adultAgeYears: number
  onAdultAgeChanged: (years: number) => void
}) {
  const [draft, setDraft] = useState(name)
  const [requiresGender, setRequiresGender] = useState(genderRequiredForPlayers)
  const [policyError, setPolicyError] = useState<'conflict' | 'error' | null>(null)
  const [ageDraft, setAgeDraft] = useState(String(adultAgeYears))
  const [ageError, setAgeError] = useState<'conflict' | 'error' | null>(null)
  const [busy, setBusy] = useState(false)
  const [denied, setDenied] = useState(false)
  const [error, setError] = useState(false)
  const t = words[language]

  async function save(event: FormEvent) {
    event.preventDefault()
    const updated = draft.trim()
    if (!updated || updated.length > 200) return
    setBusy(true)
    setError(false)
    try {
      const response = await fetch('/api/v1/club', {
        method: 'PATCH',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
        body: JSON.stringify({ name: updated }),
      })
      if ((response.status === 401 || response.status === 403)) { setDenied(true); return }
      if (!response.ok) throw new Error('Update rejected')
      onChanged(updated)
    } catch {
      setError(true)
    } finally {
      setBusy(false)
    }
  }

  async function saveGenderPolicy(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setPolicyError(null)
    try {
      const response = await fetch('/api/v1/club/player-gender-policy', {
        method: 'PATCH',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
        body: JSON.stringify({ genderRequiredForPlayers: requiresGender }),
      })
      if ((response.status === 401 || response.status === 403)) { setDenied(true); return }
      if (response.status === 409) { setPolicyError('conflict'); return }
      if (!response.ok) throw new Error('Policy rejected')
      onPolicyChanged(requiresGender)
    } catch {
      setPolicyError('error')
    } finally {
      setBusy(false)
    }
  }

  async function saveAge(event: FormEvent) {
    event.preventDefault()
    const years = parseAdultAge(ageDraft)
    if (years === null) return
    setBusy(true)
    setAgeError(null)
    const result = await saveAdultAge(years, csrfToken)
    setBusy(false)
    if (result === 'denied') setDenied(true)
    else if (result === 'saved') onAdultAgeChanged(years)
    else setAgeError(result)
  }

  if (denied) return null
  return (
    <section aria-label={t.title}>
      <h3>{t.title}</h3>
      <form onSubmit={event => { void save(event) }}>
        <label>{t.label}
          <input required maxLength={200} value={draft} onChange={event => setDraft(event.target.value)} />
        </label>
        <button type="submit" disabled={busy}>{t.save}</button>
      </form>
      {error && <p role="alert">{t.error}</p>}
      <h3>{t.policyTitle}</h3>
      <form onSubmit={event => { void saveGenderPolicy(event) }}>
        <label>{t.required}
          <input type="checkbox" checked={requiresGender} disabled={busy}
            onChange={event => setRequiresGender(event.target.checked)} />
        </label>
        <button type="submit" disabled={busy || requiresGender === genderRequiredForPlayers}>
          {t.savePolicy}
        </button>
      </form>
      {policyError && <p role="alert">{policyError === 'conflict' ? t.conflict : t.policyError}</p>}
      <h3>{t.ageTitle}</h3>
      <form onSubmit={event => { void saveAge(event) }}>
        <label>{t.ageLabel}
          <input type="number" inputMode="numeric" required min={ADULT_AGE_MIN} max={ADULT_AGE_MAX} step={1}
            value={ageDraft} disabled={busy} onChange={event => setAgeDraft(event.target.value)} />
        </label>
        <button type="submit"
          disabled={busy || parseAdultAge(ageDraft) === null || parseAdultAge(ageDraft) === adultAgeYears}>
          {t.saveAge}
        </button>
      </form>
      {ageError && <p role="alert">{ageError === 'conflict' ? t.ageConflict : t.ageError}</p>}
    </section>
  )
}
