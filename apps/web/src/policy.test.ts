import { describe, expect, it, vi } from 'vitest'
import { isAdultAge, parseAdultAge, saveAdultAge, saveClubName, saveGenderPolicy } from './policy'

describe('adult age bounds', () => {
  it('accepts only whole numbers from 13 to 25', () => {
    for (const ok of [13, 16, 18, 25]) expect(isAdultAge(ok)).toBe(true)
    for (const bad of [12, 26, 18.5, '18', null, undefined, Number.NaN]) expect(isAdultAge(bad)).toBe(false)
  })

  it('parses form input strictly', () => {
    expect(parseAdultAge('16')).toBe(16)
    expect(parseAdultAge(' 18 ')).toBe(18)
    for (const bad of ['', '12', '26', '16.0', '1e1', '-16', 'abc', '016']) expect(parseAdultAge(bad)).toBeNull()
  })
})

describe('saveAdultAge', () => {
  const csrf = 'x'.repeat(43)

  it('sends the CSRF-protected JSON patch', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, status: 204 })
    expect(await saveAdultAge(16, csrf, request)).toBe('saved')
    expect(request).toHaveBeenCalledWith('/api/v1/club/adult-age-policy', {
      method: 'PATCH',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
      body: '{"adultAgeYears":16}',
    })
  })

  it('maps denial, conflict and failures', async () => {
    for (const [status, result] of [[401, 'denied'], [403, 'denied'], [409, 'conflict'], [400, 'error'], [503, 'error']] as const) {
      expect(await saveAdultAge(16, csrf, vi.fn().mockResolvedValue({ ok: false, status }))).toBe(result)
    }
    expect(await saveAdultAge(16, csrf, vi.fn().mockRejectedValue(new Error('offline')))).toBe('error')
  })
})

describe('club settings writes', () => {
  const csrf = 'x'.repeat(43)

  it('patches the club name and the player-gender policy with CSRF', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, status: 204 })
    expect(await saveClubName('New Club', csrf, request)).toBe('ok')
    expect(await saveGenderPolicy(true, csrf, request)).toBe('ok')
    expect(request.mock.calls[0]).toEqual(['/api/v1/club', expect.objectContaining({ method: 'PATCH', body: '{"name":"New Club"}' })])
    expect(request.mock.calls[1]).toEqual(['/api/v1/club/player-gender-policy',
      expect.objectContaining({ method: 'PATCH', body: '{"genderRequiredForPlayers":true}' })])
  })

  it('surfaces the policy conflict distinctly from denial', async () => {
    expect(await saveGenderPolicy(true, csrf, vi.fn().mockResolvedValue({ ok: false, status: 409 }))).toBe('conflict')
    expect(await saveGenderPolicy(true, csrf, vi.fn().mockResolvedValue({ ok: false, status: 403 }))).toBe('forbidden')
  })
})
