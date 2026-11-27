import { useState } from 'react'
import type { Language } from './language'
import { answerable, type AttendanceResponse, type MyEvent } from './events'
import type { Notice } from './useMyEvents'

export const eventWords = {
  de: { note: 'Hinweis für den Trainer (freiwillig)', noteHint: 'Sehen nur die Trainer. Bitte keine Gesundheitsangaben.', saveNote: 'Hinweis speichern', until: 'Antworten bis {when}', answersClosed: 'Antworten geschlossen', change: 'Ändern', TRAINING: 'Training', MATCH: 'Spiel', OTHER: 'Termin', YES: 'Ja', NO: 'Nein', MAYBE: 'Vielleicht', you: 'Ich', coming: 'Kommt {name}?', comingSelf: 'Kommst du?', missing: 'Antwort fehlt', cancelled: 'Abgesagt', closed: 'Antworten nicht mehr möglich.', denied: 'Keine Berechtigung.', failed: 'Antwort nicht gespeichert. Bitte nochmals versuchen.', loading: 'Wird geladen…', error: 'Termine sind gerade nicht verfügbar. Bitte später nochmals versuchen.', open: 'Öffnen', answers: '{yes} Ja · {no} Nein · {maybe} Vielleicht' },
  en: { note: 'Note for the coach (optional)', noteHint: 'Only the coaches see it. Please no health details.', saveNote: 'Save note', until: 'Answers until {when}', answersClosed: 'Answers closed', change: 'Change', TRAINING: 'Training', MATCH: 'Match', OTHER: 'Event', YES: 'Yes', NO: 'No', MAYBE: 'Maybe', you: 'Me', coming: 'Is {name} coming?', comingSelf: 'Are you coming?', missing: 'Answer missing', cancelled: 'Cancelled', closed: 'Answers are closed for this event.', denied: 'No permission.', failed: 'Answer not saved. Please try again.', loading: 'Loading…', error: 'Events are unavailable right now. Please try again later.', open: 'Open', answers: '{yes} yes · {no} no · {maybe} maybe' },
  fr: { note: 'Remarque pour l’entraîneur (facultatif)', noteHint: 'Seuls les entraîneurs la voient. Pas d’informations de santé, s’il vous plaît.', saveNote: 'Enregistrer la remarque', until: 'Réponses jusqu’au {when}', answersClosed: 'Réponses closes', change: 'Modifier', TRAINING: 'Entraînement', MATCH: 'Match', OTHER: 'Événement', YES: 'Oui', NO: 'Non', MAYBE: 'Peut-être', you: 'Moi', coming: '{name} vient ?', comingSelf: 'Venez-vous ?', missing: 'Réponse manquante', cancelled: 'Annulé', closed: 'Les réponses sont closes pour cet événement.', denied: 'Pas d’autorisation.', failed: 'Réponse non enregistrée. Veuillez réessayer.', loading: 'Chargement…', error: 'Les événements sont indisponibles. Veuillez réessayer plus tard.', open: 'Ouvrir', answers: '{yes} oui · {no} non · {maybe} peut-être' },
  it: { note: 'Nota per l’allenatore (facoltativa)', noteHint: 'La vedono solo gli allenatori. Niente dati sulla salute, per favore.', saveNote: 'Salva nota', until: 'Risposte fino a {when}', answersClosed: 'Risposte chiuse', change: 'Modifica', TRAINING: 'Allenamento', MATCH: 'Partita', OTHER: 'Evento', YES: 'Sì', NO: 'No', MAYBE: 'Forse', you: 'Io', coming: '{name} viene?', comingSelf: 'Vieni?', missing: 'Risposta mancante', cancelled: 'Annullato', closed: 'Le risposte sono chiuse per questo evento.', denied: 'Nessuna autorizzazione.', failed: 'Risposta non salvata. Riprova.', loading: 'Caricamento…', error: 'Gli eventi non sono disponibili. Riprova più tardi.', open: 'Apri', answers: '{yes} sì · {no} no · {maybe} forse' },
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
export function AnswerButtons({ event, person, self, language, busy, onAnswer, onNote, compact = false }: {
  event: MyEvent; person: MyEvent['for'][number]; self: string | null; language: Language; busy: boolean
  onAnswer: (event: MyEvent, memberId: string, current: AttendanceResponse | null, next: AttendanceResponse) => void
  /** On the event page: a note for the coach with the answer (OpenAPI 1.39.0). */
  onNote?: (event: MyEvent, memberId: string, response: AttendanceResponse, note: string) => void
  /** In a long list an answered person shrinks to one line with Change, so open answers stand out. */
  compact?: boolean
}) {
  const t = eventWords[language]
  const [changing, setChanging] = useState(false)
  const [note, setNote] = useState(person.note ?? '')
  const label = person.id === self ? t.comingSelf : fill(t.coming, { name: person.firstName })
  const open = answerable(event)
  if (compact && person.response && !changing) return <div className="answer answered">
    <p className="answer-label">{label} <b className={'reply reply-' + person.response.toLowerCase()}>
      <span aria-hidden="true">{person.response === 'YES' ? '✓ ' : person.response === 'NO' ? '✕ ' : '? '}</span>{t[person.response]}</b>
      {person.note && <span className="muted"> · {person.note}</span>}</p>
    {open && <button type="button" className="quiet" aria-label={`${t.change}: ${label} ${event.title}`}
      onClick={() => setChanging(true)}>{t.change}</button>}
  </div>
  return <div className="answer">
    <p className="answer-label">{label}</p>
    <div className="seg" role="group" aria-label={`${label} ${event.title}`}>
      {(['YES', 'NO', 'MAYBE'] as const).map(choice => <button key={choice} type="button"
        className={person.response === choice ? 'btn on' : 'btn'} aria-pressed={person.response === choice}
        disabled={busy || !open} onClick={() => { setChanging(false); onAnswer(event, person.id, person.response, choice) }}>
        {person.response === choice && <span aria-hidden="true">✓ </span>}{t[choice]}</button>)}
    </div>
    {onNote && open && person.response && <form className="note-form" onSubmit={submit => {
      submit.preventDefault(); onNote(event, person.id, person.response!, note)
    }}>
      <label>{t.note}<input value={note} maxLength={100} aria-describedby={'note-hint-' + person.id}
        onChange={change => setNote(change.target.value)} /></label>
      <button type="submit" className="quiet" disabled={busy || note.trim() === (person.note ?? '')}>{t.saveNote}</button>
      <span id={'note-hint-' + person.id} className="muted small">{t.noteHint}</span>
    </form>}
    {(!onNote || !open) && person.note && <p className="muted small">{person.note}</p>}
  </div>
}

export function NoticeLine({ notice, language }: { notice: Notice | null; language: Language }) {
  return notice ? <p role="alert" className="notice">{eventWords[language][notice]}</p> : null
}
