import { describe, expect, it, vi } from 'vitest'
import { bulkEvents } from './bulkEvents'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const input = { teamIds: ['t'], from: '2026-10-05', to: '2026-10-16', kinds: ['TRAINING' as const], action: 'CANCEL' as const, location: 'x' }

describe('bulk events', () => {
  it('previews and applies, sending a place only for a new place', async () => {
    const request = vi.fn().mockResolvedValue(json({ matched: 4, changed: 0 }))
    expect(await bulkEvents(input, true, 'tok', request)).toEqual({ matched: 4, changed: 0 })
    expect(JSON.parse(request.mock.calls[0][1].body)).toEqual({ ...input, location: null, preview: true })
    const move = vi.fn().mockResolvedValue(json({ matched: 4, changed: 4 }))
    await bulkEvents({ ...input, action: 'SET_LOCATION' }, false, 'tok', move)
    expect(JSON.parse(move.mock.calls[0][1].body)).toMatchObject({ action: 'SET_LOCATION', location: 'x', preview: false })
  })

  it('maps refusals', async () => {
    expect(await bulkEvents(input, false, 't', vi.fn().mockResolvedValue(json({ code: 'too_many' }, 409)))).toBe('too_many')
    expect(await bulkEvents(input, false, 't', vi.fn().mockResolvedValue(json({}, 403)))).toBe('denied')
    await expect(bulkEvents(input, false, 't', vi.fn().mockResolvedValue(json({ matched: 1 })))).rejects.toThrow()
  })
})
