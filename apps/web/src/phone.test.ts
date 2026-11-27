import { describe, expect, it } from 'vitest'
import { formatPhone } from './phone'

describe('formatPhone', () => {
  it('groups Swiss and Liechtenstein numbers and leaves others alone', () => {
    expect(formatPhone('+41791234567')).toBe('+41 79 123 45 67')
    expect(formatPhone('0041 79 123 45 67')).toBe('+41 79 123 45 67')
    expect(formatPhone('0791234567')).toBe('079 123 45 67')
    expect(formatPhone('+4232345678')).toBe('+423 234 56 78')
    expect(formatPhone('+49 30 123456')).toBe('+49 30 123456')
  })
})
