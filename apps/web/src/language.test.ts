import { describe, expect, it } from 'vitest'
import { preferredLanguage, supportedLanguage } from './language'

describe('OpenClub language selection', () => {
  it('accepts the four supported primary language tags', () => {
    expect(supportedLanguage('de-CH')).toBe('de')
    expect(supportedLanguage('EN-us')).toBe('en')
    expect(supportedLanguage('fr_CH')).toBe('fr')
    expect(supportedLanguage('it')).toBe('it')
    expect(supportedLanguage('es')).toBeNull()
    expect(supportedLanguage(null)).toBeNull()
  })

  it('prefers saved selection and falls back safely', () => {
    expect(preferredLanguage('it', 'de-CH')).toBe('it')
    expect(preferredLanguage(null, 'fr-CH')).toBe('fr')
    expect(preferredLanguage('unknown', 'en-US')).toBe('en')
    expect(preferredLanguage(null, 'es-ES')).toBe('de')
  })
})
