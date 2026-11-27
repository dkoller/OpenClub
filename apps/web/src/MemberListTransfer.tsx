import { useState, type FormEvent } from 'react'
import type { Language } from './language'
import { useEditorAccess } from './editorAccess'
import { memberListExportPath, uploadMemberList, type ImportResult } from './memberList'

const words = {
  de: { title: 'Excel-Import und -Export', export: 'Mitgliederliste herunterladen (CSV für Excel)', file: 'CSV-Datei aus Excel', check: 'Datei prüfen', hint: 'In Excel «Speichern unter» → «CSV UTF-8». Spalten: Vorname, Nachname, Geburtsdatum (Pflicht), E-Mail, Telefon, Geschlecht, Spieler. Bestehende Mitglieder werden nie geändert.', line: 'Zeile', name: 'Name', status: 'Ergebnis', OK: 'wird importiert', DUPLICATE: 'existiert bereits', INVALID: 'fehlerhaft', summary: '{ok} werden importiert, {duplicate} existieren bereits, {invalid} fehlerhaft (werden nicht importiert).', commit: '{ok} Mitglieder importieren', done: '{created} Mitglieder importiert.', unreadable: 'Die Datei kann nicht gelesen werden: Kopfzeile mit Vorname, Nachname und Geburtsdatum, höchstens 1000 Zeilen.', denied: 'Keine Berechtigung.', failed: 'Import fehlgeschlagen.', FIRST_NAME: 'Vorname', LAST_NAME: 'Nachname', DATE_OF_BIRTH: 'Geburtsdatum', EMAIL: 'E-Mail', PHONE: 'Telefon', GENDER: 'Geschlecht', PLAYER: 'Spieler (ja/nein)', ADULT_CONTACTS: 'Erwachsene brauchen E-Mail und Telefon', GENDER_REQUIRED: 'Spieler brauchen ein Geschlecht', DUPLICATE_IN_FILE: 'doppelt in der Datei' },
  en: { title: 'Excel import and export', export: 'Download member list (CSV for Excel)', file: 'CSV file from Excel', check: 'Check file', hint: 'In Excel, Save As → "CSV UTF-8". Columns: First name, Last name, Date of birth (required), Email, Phone, Gender, Player. Existing members are never changed.', line: 'Line', name: 'Name', status: 'Result', OK: 'will be imported', DUPLICATE: 'already exists', INVALID: 'has problems', summary: '{ok} will be imported, {duplicate} already exist, {invalid} have problems (not imported).', commit: 'Import {ok} members', done: '{created} members imported.', unreadable: 'The file cannot be read: it needs a header row with first name, last name and date of birth, and at most 1,000 rows.', denied: 'No permission.', failed: 'Import failed.', FIRST_NAME: 'first name', LAST_NAME: 'last name', DATE_OF_BIRTH: 'date of birth', EMAIL: 'email', PHONE: 'phone', GENDER: 'gender', PLAYER: 'player (yes/no)', ADULT_CONTACTS: 'adults need email and phone', GENDER_REQUIRED: 'players need a gender', DUPLICATE_IN_FILE: 'twice in the file' },
  fr: { title: 'Import et export Excel', export: 'Télécharger la liste des membres (CSV pour Excel)', file: 'Fichier CSV d’Excel', check: 'Vérifier le fichier', hint: 'Dans Excel, Enregistrer sous → « CSV UTF-8 ». Colonnes : Prénom, Nom, Date de naissance (obligatoires), E-mail, Téléphone, Genre, Joueur. Les membres existants ne sont jamais modifiés.', line: 'Ligne', name: 'Nom', status: 'Résultat', OK: 'sera importé', DUPLICATE: 'existe déjà', INVALID: 'a des erreurs', summary: '{ok} seront importés, {duplicate} existent déjà, {invalid} ont des erreurs (non importés).', commit: 'Importer {ok} membres', done: '{created} membres importés.', unreadable: 'Le fichier est illisible : il faut une ligne d’en-tête avec prénom, nom et date de naissance, et au plus 1000 lignes.', denied: 'Pas d’autorisation.', failed: 'L’import a échoué.', FIRST_NAME: 'prénom', LAST_NAME: 'nom', DATE_OF_BIRTH: 'date de naissance', EMAIL: 'e-mail', PHONE: 'téléphone', GENDER: 'genre', PLAYER: 'joueur (oui/non)', ADULT_CONTACTS: 'les adultes ont besoin d’un e-mail et d’un téléphone', GENDER_REQUIRED: 'les joueurs ont besoin d’un genre', DUPLICATE_IN_FILE: 'en double dans le fichier' },
  it: { title: 'Import ed export Excel', export: 'Scarica l’elenco dei membri (CSV per Excel)', file: 'File CSV da Excel', check: 'Verifica file', hint: 'In Excel, Salva con nome → «CSV UTF-8». Colonne: Nome, Cognome, Data di nascita (obbligatori), Email, Telefono, Genere, Giocatore. I membri esistenti non vengono mai modificati.', line: 'Riga', name: 'Nome', status: 'Esito', OK: 'sarà importato', DUPLICATE: 'esiste già', INVALID: 'ha errori', summary: '{ok} saranno importati, {duplicate} esistono già, {invalid} hanno errori (non importati).', commit: 'Importa {ok} membri', done: '{created} membri importati.', unreadable: 'Il file non è leggibile: serve una riga di intestazione con nome, cognome e data di nascita, al massimo 1000 righe.', denied: 'Nessuna autorizzazione.', failed: 'Import non riuscito.', FIRST_NAME: 'nome', LAST_NAME: 'cognome', DATE_OF_BIRTH: 'data di nascita', EMAIL: 'email', PHONE: 'telefono', GENDER: 'genere', PLAYER: 'giocatore (sì/no)', ADULT_CONTACTS: 'gli adulti hanno bisogno di email e telefono', GENDER_REQUIRED: 'i giocatori hanno bisogno di un genere', DUPLICATE_IN_FILE: 'doppio nel file' },
} satisfies Record<Language, Record<string, string>>

const fill = (text: string, values: Record<string, number | null>) =>
  text.replace(/\{(\w+)\}/g, (_, key: string) => String(values[key] ?? 0))

/**
 * Member list as CSV for editors (OpenAPI 1.13.0). The import is always checked first; only the rows marked
 * "will be imported" are created, after a second, explicit confirmation.
 */
export function MemberListTransfer({ language, csrfToken, onImported }: {
  language: Language; csrfToken: string; onImported?: () => void
}) {
  const t = words[language]
  const editor = useEditorAccess()
  const [file, setFile] = useState<File | null>(null)
  const [preview, setPreview] = useState<ImportResult | null>(null)
  const [notice, setNotice] = useState<{ text: string; ok: boolean } | null>(null)
  const [busy, setBusy] = useState(false)

  async function send(mode: 'preview' | 'commit') {
    if (!file) return
    setBusy(true); setNotice(null)
    try {
      const result = await uploadMemberList(file, mode, csrfToken)
      if (result === 'unreadable') { setPreview(null); setNotice({ text: t.unreadable, ok: false }); return }
      if (result === 'denied') { setPreview(null); setNotice({ text: t.denied, ok: false }); return }
      if (mode === 'preview') { setPreview(result); return }
      setPreview(null); setFile(null)
      setNotice({ text: fill(t.done, { created: result.created }), ok: true })
      onImported?.()
    } catch { setNotice({ text: t.failed, ok: false }) } finally { setBusy(false) }
  }

  if (editor !== 'editor') return null
  return (
    <section aria-label={t.title}>
      <h2 className="vh">{t.title}</h2>
      <p><a href={memberListExportPath(language)} download>{t.export}</a></p>
      <form className="stack file-form" onSubmit={(event: FormEvent) => { event.preventDefault(); void send('preview') }}>
        <label>{t.file}<input type="file" accept=".csv,text/csv" required
          onChange={event => { setFile(event.target.files?.[0] ?? null); setPreview(null); setNotice(null) }} /></label>
        <button type="submit" disabled={busy || !file}>{t.check}</button>
      </form>
      <p className="muted small">{t.hint}</p>
      {preview && <>
        <p role="status">{fill(t.summary, preview.counts)}</p>
        <table className="cards">
          <thead><tr><th scope="col">{t.line}</th><th scope="col">{t.name}</th><th scope="col">{t.status}</th></tr></thead>
          <tbody>{preview.rows.map(row => <tr key={row.line}>
            <td data-label={t.line}>{row.line}</td><th scope="row">{row.firstName} {row.lastName}</th>
            <td data-label={t.status}>{t[row.status]}{row.problems.length > 0 && <>: {row.problems.map(problem => t[problem]).join(', ')}</>}</td>
          </tr>)}</tbody>
        </table>
        {preview.counts.ok > 0 && <button type="button" disabled={busy} onClick={() => { void send('commit') }}>
          {fill(t.commit, preview.counts)}</button>}
      </>}
      {notice && <p role={notice.ok ? 'status' : 'alert'}>{notice.text}</p>}
    </section>
  )
}
