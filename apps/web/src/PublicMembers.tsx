import { useEffect, useState } from 'react'
import type { Language } from './language'
import { Reveal } from './Reveal'
import { readPublicNames, type PublicName } from './publicMembers'

const words = {
  de: { show: 'Mitglieder anzeigen', heading: 'Mitglieder', loading: 'Mitglieder werden geladen…', empty: 'Noch keine Mitglieder.', error: 'Mitglieder sind momentan nicht verfügbar.', retry: 'Erneut versuchen' },
  en: { show: 'Show members', heading: 'Members', loading: 'Loading members…', empty: 'No members yet.', error: 'Members are temporarily unavailable.', retry: 'Try again' },
  fr: { show: 'Afficher les membres', heading: 'Membres', loading: 'Chargement des membres…', empty: 'Aucun membre pour le moment.', error: 'Membres momentanément indisponibles.', retry: 'Réessayer' },
  it: { show: 'Mostra i membri', heading: 'Membri', loading: 'Caricamento dei membri…', empty: 'Nessun membro per ora.', error: 'Membri temporaneamente non disponibili.', retry: 'Riprova' },
} satisfies Record<Language, Record<string, string>>

/** [collapsed]: on the signed-out start page the names wait behind a button, so sign-in and joining come first. */
export function PublicMembers({ language, revision = 0, collapsed = false }: { language: Language; revision?: number; collapsed?: boolean }) {
  const [names, setNames] = useState<PublicName[] | null>(null)
  const [error, setError] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const text = words[language]

  useEffect(() => {
    const controller = new AbortController()
    setNames(null)
    setError(false)
    readPublicNames(controller.signal)
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
        <Reveal label={text.show} open={!collapsed}><ul>{names.map((item, index) => <li key={index}>{item.displayName}</li>)}</ul></Reveal>
      )}
    </section>
  )
}
