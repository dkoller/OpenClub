import type { Language } from './language'

/**
 * The app's language on this device. Members find it under Family → My profile; visitors and signed-in people who are
 * not yet members find it at the bottom of their page. Not in the header: it is set once, not used on every page.
 */
export function LanguageChoice({ language, label, onChange }: { language: Language; label: string; onChange: (next: Language) => void }) {
  return <p className="field"><label htmlFor="language">{label}</label>
    <select id="language" value={language} onChange={event => onChange(event.target.value as Language)}>
      <option value="de" lang="de">Deutsch</option><option value="fr" lang="fr">Français</option>
      <option value="it" lang="it">Italiano</option><option value="en" lang="en">English</option>
    </select></p>
}
