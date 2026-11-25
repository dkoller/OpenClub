import { describe, expect, it, vi } from 'vitest'
import { sendCoachMessage } from './coachMessages'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

describe('coach messages', () => {
  it('sends the audience, subject and text and reads the count', async () => {
    const request = vi.fn().mockResolvedValue(json({ members: 12 }))
    expect(await sendCoachMessage('e', 'NO_ANSWER', 'Shirts', 'Bring them', 'tok', request)).toEqual({ members: 12 })
    expect(request.mock.calls[0]).toMatchObject(['/api/v1/events/e/messages',
      { method: 'POST', body: '{"audience":"NO_ANSWER","subject":"Shirts","body":"Bring them"}', headers: { 'X-CSRF-Token': 'tok' } }])
  })

  it('maps the refusals', async () => {
    for (const code of ['limit', 'email_off', 'not_match', 'closed'] as const) {
      expect(await sendCoachMessage('e', 'ALL', 's', 'b', 't', vi.fn().mockResolvedValue(json({ code }, 409)))).toBe(code)
    }
    expect(await sendCoachMessage('e', 'ALL', 's', 'b', 't', vi.fn().mockResolvedValue(json({}, 403)))).toBe('denied')
  })
})
