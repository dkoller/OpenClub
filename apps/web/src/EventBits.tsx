import type { Language } from './language'
import { answerable, type AttendanceResponse, type MyEvent } from './events'
import type { Notice } from './useMyEvents'

export const eventWords = {
  de: { TRAINING: 'Training', MATCH: 'Spiel', OTHER: 'Termin', YES: 'Ja', NO: 'Nein', MAYBE: 'Vielleicht', you: 'Ich', coming: 'Kommt {name}?', comingSelf: 'Kommst du?', missing: 'Antwort fehlt', cancelled: 'Abgesagt', closed: 'Antworten nicht mehr möglich.', denied: 'Keine Berechtigung.', failed: 'Antwort nicht gespeichert. Bitte nochmals versuchen.', loading: 'Wird geladen…', error: 'Termine sind gerade nicht verfügbar. Bitte später nochmals versuchen.', open: 'Öffnen', answers: '{yes} Ja · {no} Nein · {maybe} Vielleicht' },
  en: { TRAINING: 'Training', MATCH: 'Match', OTHER: 'Event', YES: 'Yes', NO: 'No', MAYBE: 'Maybe', you: 'Me', coming: 'Is {name} coming?', comingSelf: 'Are you coming?', missing: 'Answer missing', cancelled: 'Cancelled', closed: 'Answers are closed for this event.', denied: 'No permission.', failed: 'Answer not saved. Please try again.', loading: 'Loading…', error: 'Events are unavailable right now. Please try again later.', open: 'Open', answers: '{yes} yes · {no} no · {maybe} maybe' },
  fr: { TRAINING: 'Entraînement', MATCH: 'Match', OTHER: 'Événement', YES: 'Oui', NO: 'Non', MAYBE: 'Peut-être', you: 'Moi', coming: '{name} vient ?', comingSelf: 'Tu viens ?', missing: 'Réponse manquante', cancelled: 'Annulé', closed: 'Les réponses sont closes pour cet événement.', denied: 'Pas d’autorisation.', failed: 'Réponse non enregistrée. Réessaie.', loading: 'Chargement…', error: 'Les événements sont indisponibles. Réessaie plus tard.', open: 'Ouvrir', answers: '{yes} oui · {no} non · {maybe} peut-être' },
  it: { TRAINING: 'Allenamento', MATCH: 'Partita', OTHER: 'Evento', YES: 'Sì', NO: 'No', MAYBE: 'Forse', you: 'Io', coming: '{name} viene?', comingSelf: 'Vieni?', missing: 'Risposta mancante', cancelled: 'Annullato', closed: 'Le risposte sono chiuse per questo evento.', denied: 'Nessuna autorizzazione.', failed: 'Risposta non salvata. Riprova.', loading: 'Caricamento…', error: 'Gli eventi non sono disponibili. Riprova più tardi.', open: 'Apri', answers: '{yes} sì · {no} no · {maybe} forse' },
} satisfies Record<Language, Record<string, string>>

export const fill = (text: string, values: Record<string, string | number>) =>
  text.replace(/\{(\w+)\}/g, (_, key: string) => String(values[key] ?? ''))

/** Short local date and time, Swiss style: "Sa 28.11., 10:00". */
export function shortWhen(instant: string, language: Language): string {
  const date = new Date(instant)
  const locale = { de: 'de-CH', en: 'en-GB', fr: 'fr-CH', it: 'it-CH' }[language]
  const day = new Intl.DateTimeFormat(locale, { weekday: 'short' }).format(date).replace('.', '')
  const dm = `${String(date.getDate()).padStart(2, '0')}.${String(date.getMonth() + 1).padStart(2, '0')}.`
  const time = new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', hour12: false }).format(date)
  return `${day} ${dm}, ${time}`
}

export function DateBlock({ instant, language }: { instant: string; language: Language }) {
  const date = new Date(instant)
  const locale = { de: 'de-CH', en: 'en-GB', fr: 'fr-CH', it: 'it-CH' }[language]
  return <span className="date" aria-hidden="true">
    <span>{new Intl.DateTimeFormat(locale, { weekday: 'short' }).format(date).replace('.', '')}</span>
    <b>{String(date.getDate()).padStart(2, '0')}.{String(date.getMonth() + 1).padStart(2, '0')}.</b>
  </span>
}

/** Yes / No / Maybe for one person; the chosen answer is pressed and carries a check mark, not only a colour. */
export function AnswerButtons({ event, person, self, language, busy, onAnswer }: {
  event: MyEvent; person: MyEvent['for'][number]; self: string | null; language: Language; busy: boolean
  onAnswer: (event: MyEvent, memberId: string, current: AttendanceResponse | null, next: AttendanceResponse) => void
}) {
  const t = eventWords[language]
  const label = person.id === self ? t.comingSelf : fill(t.coming, { name: person.firstName })
  const open = answerable(event)
  return <div className="answer">
    <p className="answer-label">{label}</p>
    <div className="seg" role="group" aria-label={`${label} ${event.title}`}>
      {(['YES', 'NO', 'MAYBE'] as const).map(choice => <button key={choice} type="button"
        className={person.response === choice ? 'btn on' : 'btn'} aria-pressed={person.response === choice}
        disabled={busy || !open} onClick={() => onAnswer(event, person.id, person.response, choice)}>
        {person.response === choice && <span aria-hidden="true">✓ </span>}{t[choice]}</button>)}
    </div>
  </div>
}

export function NoticeLine({ notice, language }: { notice: Notice | null; language: Language }) {
  return notice ? <p role="alert" className="notice">{eventWords[language][notice]}</p> : null
}
