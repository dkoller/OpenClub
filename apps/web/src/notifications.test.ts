import { describe, expect, it, vi } from 'vitest'
import { parseNotificationStatus, readNotifications, saveNotifications } from './notifications'

const status = { eventChanges: true, newEvents: false, announcements: true, shiftReminders: true, helpersWanted: false, answerReminders: true, squadCalls: false, teamMessages: true, language: 'fr',
  emailEnabled: true, hasEmail: true }
const res = (status: number, body?: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body })

describe('notification settings', () => {
  it('accepts exactly the documented settings', () => {
    expect(parseNotificationStatus(status)).toEqual(status)
    for (const broken of [{ ...status, extra: 1 }, { ...status, language: 'es' }, { ...status, newEvents: 'no' },
      { ...status, hasEmail: undefined }, { ...status, helpersWanted: 'no' }, { ...status, answerReminders: undefined }, null, []]) {
      expect(() => parseNotificationStatus(broken)).toThrow()
    }
  })

  it('reads uncached and maps refusals', async () => {
    const request = vi.fn().mockResolvedValue(res(200, status))
    expect(await readNotifications(request)).toEqual(status)
    expect(request.mock.calls[0]).toEqual(['/api/v1/me/notifications', { credentials: 'same-origin', cache: 'no-store' }])
    expect(await readNotifications(vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    await expect(readNotifications(vi.fn().mockResolvedValue(res(503)))).rejects.toThrow()
  })

  it('sends only the seven settings with CSRF', async () => {
    const request = vi.fn().mockResolvedValue(res(204))
    expect(await saveNotifications({ ...status, language: 'it' } as never, 'c', request)).toBe('ok')
    expect(request.mock.calls[0]).toEqual(['/api/v1/me/notifications', { method: 'PUT', credentials: 'same-origin',
      headers: { 'X-CSRF-Token': 'c', 'Content-Type': 'application/json' },
      body: '{"eventChanges":true,"newEvents":false,"announcements":true,"shiftReminders":true,"helpersWanted":false,"answerReminders":true,"squadCalls":false,"teamMessages":true,"language":"it"}' }])
    expect(await saveNotifications(status as never, 'c', vi.fn().mockResolvedValue(res(400)))).toBe('invalid')
    expect(await saveNotifications(status as never, 'c', vi.fn().mockResolvedValue(res(401)))).toBe('denied')
    await expect(saveNotifications(status as never, 'c', vi.fn().mockResolvedValue(res(500)))).rejects.toThrow()
  })
})
