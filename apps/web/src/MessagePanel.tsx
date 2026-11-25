import { useState, type FormEvent } from 'react'
import type { Language } from './language'
import type { TeamEvent } from './events'
import { sendCoachMessage, type Audience } from './coachMessages'

const words = {
  de: { title: 'Nachricht an das Team', hint: 'Wird per E-Mail verschickt (bei Kindern an die Eltern). Niemand sieht die Adressen. Höchstens 10 Nachrichten pro Team und Tag.', who: 'An wen', ALL: 'Alle im Team', YES: 'Alle, die zugesagt haben', NO_ANSWER: 'Alle ohne Antwort', SQUAD: 'Das Aufgebot', subject: 'Betreff', body: 'Nachricht', send: 'Senden', sent: 'An {n} Mitglieder verschickt.', limit: 'Für heute sind schon 10 Nachrichten verschickt worden.', email_off: 'Dieser Verein versendet keine E-Mails.', closed: 'Dieser Termin ist zu lange vorbei.', not_match: 'Nur Spiele haben ein Aufgebot.', invalid: 'Bitte Betreff (höchstens 150 Zeichen, eine Zeile) und Nachricht prüfen.', failed: 'Nicht verschickt. Bitte später nochmals versuchen.' },
  en: { title: 'Message to the team', hint: 'Sent by email (to parents for children). Nobody sees the addresses. At most 10 messages per team and day.', who: 'To whom', ALL: 'Everyone in the team', YES: 'Everyone who said yes', NO_ANSWER: 'Everyone without an answer', SQUAD: 'The squad', subject: 'Subject', body: 'Message', send: 'Send', sent: 'Sent to {n} members.', limit: '10 messages have already been sent today.', email_off: 'This club does not send emails.', closed: 'This event is too long ago.', not_match: 'Only matches have a squad.', invalid: 'Please check the subject (at most 150 characters, one line) and the message.', failed: 'Not sent. Please try again later.' },
  fr: { title: 'Message à l’équipe', hint: 'Envoyé par e-mail (aux parents pour les enfants). Personne ne voit les adresses. 10 messages au plus par équipe et par jour.', who: 'À qui', ALL: 'Toute l’équipe', YES: 'Ceux qui ont dit oui', NO_ANSWER: 'Ceux qui n’ont pas répondu', SQUAD: 'Les convoqués', subject: 'Objet', body: 'Message', send: 'Envoyer', sent: 'Envoyé à {n} membres.', limit: '10 messages ont déjà été envoyés aujourd’hui.', email_off: 'Ce club n’envoie pas d’e-mails.', closed: 'Cet événement est trop ancien.', not_match: 'Seuls les matchs ont une convocation.', invalid: 'Veuillez vérifier l’objet (150 caractères au plus, une ligne) et le message.', failed: 'Non envoyé. Veuillez réessayer plus tard.' },
  it: { title: 'Messaggio alla squadra', hint: 'Inviato via email (ai genitori per i figli). Nessuno vede gli indirizzi. Al massimo 10 messaggi per squadra al giorno.', who: 'A chi', ALL: 'Tutta la squadra', YES: 'Chi ha detto sì', NO_ANSWER: 'Chi non ha risposto', SQUAD: 'I convocati', subject: 'Oggetto', body: 'Messaggio', send: 'Invia', sent: 'Inviato a {n} membri.', limit: 'Oggi sono già stati inviati 10 messaggi.', email_off: 'Questo club non invia email.', closed: 'Questo evento è troppo lontano nel passato.', not_match: 'Solo le partite hanno una convocazione.', invalid: 'Controllate l’oggetto (al massimo 150 caratteri, una riga) e il messaggio.', failed: 'Non inviato. Riprovate più tardi.' },
} satisfies Record<Language, Record<string, string>>

/** Coach event page: one email to part of the event's people, through the server. */
export function MessagePanel({ event, language, csrfToken }: { event: TeamEvent; language: Language; csrfToken: string }) {
  const t = words[language]
  const audiences: Audience[] = event.kind === 'MATCH' ? ['ALL', 'YES', 'NO_ANSWER', 'SQUAD'] : ['ALL', 'YES', 'NO_ANSWER']
  const [audience, setAudience] = useState<Audience>('ALL')
  const [subject, setSubject] = useState('')
  const [body, setBody] = useState('')
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null)
  async function send(form: FormEvent) {
    form.preventDefault()
    setBusy(true); setNotice(null)
    try {
      const outcome = await sendCoachMessage(event.id, audience, subject.trim(), body.trim(), csrfToken)
      if (typeof outcome === 'object') {
        setNotice({ ok: true, text: t.sent.replace('{n}', String(outcome.members)) }); setSubject(''); setBody('')
      } else setNotice({ ok: false, text: outcome === 'denied' ? t.failed : t[outcome] })
    } catch { setNotice({ ok: false, text: t.failed }) } finally { setBusy(false) }
  }
  return <details className="card pad">
    <summary>{t.title}</summary>
    <form className="stack" onSubmit={form => { void send(form) }}>
      <p className="muted small">{t.hint}</p>
      <fieldset><legend>{t.who}</legend>{audiences.map(item => <label key={item} className="check">
        <input type="radio" name={'audience-' + event.id} checked={audience === item} onChange={() => setAudience(item)} />{t[item]}</label>)}</fieldset>
      <label>{t.subject}<input value={subject} required maxLength={150} onChange={e => setSubject(e.target.value)} /></label>
      <label>{t.body}<textarea value={body} required maxLength={5000} rows={5} onChange={e => setBody(e.target.value)} /></label>
      <button type="submit" className="btn primary" disabled={busy}>{t.send}</button>
      {notice && <p role={notice.ok ? 'status' : 'alert'}>{notice.text}</p>}
    </form>
  </details>
}
