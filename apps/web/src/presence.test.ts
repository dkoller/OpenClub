import { describe, expect, it, vi } from 'vitest'
import { checkJs, jsFileHref, readPresence, savePresence } from './presence'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const person = { memberId: 'm1', displayName: 'Lea M.', role: 'PARTICIPANT', response: 'YES', present: false }

describe('presence', () => {
  it('reads exactly the documented shape and hides itself without the right', async () => {
    expect(await readPresence('e1', vi.fn().mockResolvedValue(json({ open: true, items: [person] }))))
      .toEqual({ open: true, items: [person] })
    for (const bad of [{ open: true, items: [{ ...person, phone: '079' }] }, { open: 'yes', items: [] },
      { open: true, items: [{ ...person, role: 'COACH' }] }]) {
      await expect(readPresence('e1', vi.fn().mockResolvedValue(json(bad)))).rejects.toThrow()
    }
    for (const status of [401, 403, 404]) {
      expect(await readPresence('e1', vi.fn().mockResolvedValue(json({}, status)))).toBe('denied')
    }
  })

  it('saves the ticked IDs with the CSRF token and maps the answers', async () => {
    const request = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    expect(await savePresence('e/1', ['a', 'b'], 'token', request)).toBe('ok')
    expect(request.mock.calls[0][0]).toBe('/api/v1/events/e%2F1/presence')
    expect(request.mock.calls[0][1]).toMatchObject({ method: 'PUT', body: '{"present":["a","b"]}',
      headers: { 'X-CSRF-Token': 'token', 'Content-Type': 'application/json' } })
    expect(await savePresence('e', [], 't', vi.fn().mockResolvedValue(json({ code: 'not_started' }, 409)))).toBe('not_started')
    expect(await savePresence('e', [], 't', vi.fn().mockResolvedValue(json({}, 400)))).toBe('invalid')
  })

  it('checks the J+S file and builds its address for the same range', async () => {
    const check = { from: '2026-08-01', to: '2026-11-25', rows: 3, withoutNumber: ['Lea M.'], trainingsWithoutPlace: [] }
    const request = vi.fn().mockResolvedValue(json(check))
    expect(await checkJs('t1', '2026-08-01', '2026-11-25', request)).toEqual(check)
    expect(request.mock.calls[0][0]).toBe('/api/v1/teams/t1/js-attendance?from=2026-08-01&to=2026-11-25')
    expect(jsFileHref('t1', '', '')).toBe('/api/v1/teams/t1/js-attendance.csv')
    expect(await checkJs('t1', '', '', vi.fn().mockResolvedValue(json({}, 400)))).toBe('invalid')
    await expect(checkJs('t1', '', '', vi.fn().mockResolvedValue(json({ ...check, extra: 1 })))).rejects.toThrow()
  })
})
