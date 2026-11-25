import { describe, expect, it, vi } from 'vitest'
import { changeSubscription, keyBytes, readPushStatus } from './push'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

describe('push', () => {
  it('reads the status strictly', async () => {
    const status = { enabled: true, publicKey: 'BEl6', devices: 1 }
    expect(await readPushStatus(vi.fn().mockResolvedValue(json(status)))).toEqual(status)
    await expect(readPushStatus(vi.fn().mockResolvedValue(json({ ...status, extra: 1 })))).rejects.toThrow()
    expect(await readPushStatus(vi.fn().mockResolvedValue(json({}, 403)))).toBe('denied')
  })

  it('sends the endpoint to subscribe and unsubscribe', async () => {
    const request = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    expect(await changeSubscription('https://fcm.googleapis.com/x', true, 'tok', request)).toBe('ok')
    expect(request.mock.calls[0][1]).toMatchObject({ method: 'PUT', body: '{"endpoint":"https://fcm.googleapis.com/x"}' })
    expect(await changeSubscription('e', false, 't', vi.fn().mockResolvedValue(json({ code: 'push_off' }, 409)))).toBe('off')
  })

  it('decodes the base64url key', () => {
    expect(Array.from(keyBytes('AQID_-8'))).toEqual([1, 2, 3, 255, 239])
  })
})
