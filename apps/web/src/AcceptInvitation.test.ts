import { describe, expect, it } from 'vitest'
import { pastedSecret } from './AcceptInvitation'

const secret = 'Ab_-'.repeat(10) + 'xyz'
describe('pastedSecret', () => {
  it('accepts a bare code or a full link and trims', () => {
    expect(pastedSecret(secret)).toBe(secret)
    expect(pastedSecret('  ' + secret + '\n')).toBe(secret)
    expect(pastedSecret('https://club.example/#invite=' + secret)).toBe(secret)
  })
  it('rejects anything else', () => {
    for (const bad of ['', 'short', 'https://club.example/', 'https://club.example/#x=' + secret, secret + 'a']) {
      expect(pastedSecret(bad)).toBeNull()
    }
  })
})
