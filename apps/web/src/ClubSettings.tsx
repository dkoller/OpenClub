import { useState, type FormEvent } from 'react'
import type { Language } from './language'

const words = {
  de: { title: 'Vereinsname bearbeiten', label: 'Vereinsname', save: 'Speichern', error: 'Vereinsname konnte nicht gespeichert werden.' },
  en: { title: 'Edit club name', label: 'Club name', save: 'Save', error: 'Could not update club name.' },
  fr: { title: 'Modifier le nom du club', label: 'Nom du club', save: 'Enregistrer', error: 'Impossible de modifier le nom du club.' },
  it: { title: 'Modifica nome del club', label: 'Nome del club', save: 'Salva', error: 'Impossibile aggiornare il nome del club.' },
} satisfies Record<Language, Record<string, string>>

export function ClubSettings({ language, name, csrfToken, onChanged }: {
  language: Language
  name: string
  csrfToken: string
  onChanged: (name: string) => void
}) {
  const [draft, setDraft] = useState(name)
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
      if (response.status === 403) { setDenied(true); return }
      if (!response.ok) throw new Error('Update rejected')
      onChanged(updated)
    } catch {
      setError(true)
    } finally {
      setBusy(false)
    }
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
    </section>
  )
}
