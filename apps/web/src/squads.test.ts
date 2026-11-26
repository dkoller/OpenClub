import { describe, expect, it, vi } from 'vitest'
import { meetInstant, readSquad, saveSquad, toTime } from './squads'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const squad = { open: true, meetAt: null, meetingPoint: 'Parkplatz', items: [{ memberId: 'm', displayName: 'Lea M.', response: null, called: true }] }

describe('squads', () => {
  it('reads strictly and hides itself for non-matches and non-managers', async () => {
    expect(await readSquad('e', vi.fn().mockResolvedValue(json(squad)))).toEqual(squad)
    for (const status of [403, 404, 409]) expect(await readSquad('e', vi.fn().mockResolvedValue(json({}, status)))).toBe('denied')
    await expect(readSquad('e', vi.fn().mockResolvedValue(json({ ...squad, items: [{ ...squad.items[0], phone: '1' }] })))).rejects.toThrow()
  })

  it('saves the called members with the meeting details', async () => {
    const request = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    expect(await saveSquad('e', ['m'], '2026-11-28T08:00:00.000Z', null, 'tok', request)).toBe('ok')
    expect(request.mock.calls[0][1]).toMatchObject({ method: 'PUT', body: '{"memberIds":["m"],"meetAt":"2026-11-28T08:00:00.000Z","meetingPoint":null}' })
    expect(await saveSquad('e', [], null, null, 't', vi.fn().mockResolvedValue(json({ code: 'closed' }, 409)))).toBe('closed')
  })
})

describe('meeting time on the match day', () => {
  it('keeps the match day and moves a time after kick-off to the evening before', () => {
    const start = new Date(2026, 11, 1, 10, 0).toISOString()
    expect(meetInstant('09:15', start)).toBe(new Date(2026, 11, 1, 9, 15).toISOString())
    expect(meetInstant('10:00', start)).toBe(start)
    expect(meetInstant('18:00', start)).toBe(new Date(2026, 10, 30, 18, 0).toISOString())
    expect(meetInstant('', start)).toBeNull()
    expect(toTime(new Date(2026, 11, 1, 9, 5).toISOString())).toBe('09:05')
  })
})
