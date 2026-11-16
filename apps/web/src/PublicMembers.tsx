import { useEffect, useState } from 'react'
import type { Language } from './language'

type PublicName = { displayName: string }

const words = {
  de: { heading: 'Mitglieder', loading: 'Mitglieder werden geladen…', empty: 'Noch keine Mitglieder.', error: 'Mitglieder sind momentan nicht verfügbar.', retry: 'Erneut versuchen' },
  en: { heading: 'Members', loading: 'Loading members…', empty: 'No members yet.', error: 'Members are temporarily unavailable.', retry: 'Try again' },
  fr: { heading: 'Membres', loading: 'Chargement des membres…', empty: 'Aucun membre pour le moment.', error: 'Membres momentanément indisponibles.', retry: 'Réessayer' },
  it: { heading: 'Membri', loading: 'Caricamento dei membri…', empty: 'Nessun membro per ora.', error: 'Membri temporaneamente non disponibili.', retry: 'Riprova' },
} satisfies Record<Language, Record<string, string>>

export function PublicMembers({ language, revision = 0 }: { language: Language; revision?: number }) {
  const [names, setNames] = useState<PublicName[] | null>(null)
  const [error, setError] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const text = words[language]

  useEffect(() => {
    const controller = new AbortController()
    fetch('/api/v1/public/members', { signal: controller.signal, credentials: 'omit' })
      .then(async response => {
        if (!response.ok) throw new Error('Member directory unavailable')
        const value: unknown = await response.json()
        if (!Array.isArray(value) || !value.every(item =>
          typeof item === 'object' && item !== null &&
          Object.keys(item).length === 1 && typeof item.displayName === 'string'
        )) throw new Error('Unexpected directory response')
        return value as PublicName[]
      })
      .then(value => { if (!controller.signal.aborted) setNames(value) })
      .catch(() => { if (!controller.signal.aborted) setError(true) })
    return () => controller.abort()
  }, [attempt, revision])

  return (
    <section aria-labelledby="public-members-heading">
      <h2 id="public-members-heading">{text.heading}</h2>
      {error ? (
        <p role="alert">{text.error}{' '}
          <button type="button" onClick={() => { setError(false); setNames(null); setAttempt(a => a + 1) }}>
            {text.retry}
          </button>
        </p>
      ) : names === null ? (
        <p aria-live="polite">{text.loading}</p>
      ) : names.length === 0 ? (
        <p>{text.empty}</p>
      ) : (
        <ul>{names.map((item, index) => <li key={index}>{item.displayName}</li>)}</ul>
      )}
    </section>
  )
}
