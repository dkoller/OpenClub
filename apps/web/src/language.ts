export type Language = 'de' | 'en' | 'fr' | 'it'

export function supportedLanguage(value: string | null | undefined): Language | null {
  const primary = value?.split(/[-_]/, 1)[0]?.toLowerCase()
  return primary === 'de' || primary === 'en' || primary === 'fr' || primary === 'it'
    ? primary : null
}

export function preferredLanguage(saved: string | null, browser: string): Language {
  return supportedLanguage(saved) ?? supportedLanguage(browser) ?? 'de'
}

export function readPreferredLanguage(): Language {
  try {
    return preferredLanguage(window.localStorage.getItem('openclub.language'), navigator.language)
  } catch {
    return supportedLanguage(navigator.language) ?? 'de'
  }
}
