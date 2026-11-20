import { describe, expect, it, vi } from 'vitest'
import { createFeed, readFeedStatus, revokeFeed } from './calendarFeed'

const res = (status: number, body?: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body })
const url = 'https://club.example/api/v1/calendar/' + 'a'.repeat(43)

describe('calendar feed', () => {
  it('reads the status strictly', async () => {
    const request = vi.fn().mockResolvedValue(res(200, { active: true, createdAt: '2026-11-20T08:00:00Z' }))
    expect(await readFeedStatus(request)).toEqual({ active: true, createdAt: '2026-11-20T08:00:00Z' })
    expect(request.mock.calls[0]).toEqual(['/api/v1/me/calendar-feed', { credentials: 'same-origin', cache: 'no-store' }])
    expect(await readFeedStatus(vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    for (const broken of [{ active: true }, { active: 'yes', createdAt: null }, { active: true, createdAt: null, url }]) {
      await expect(readFeedStatus(vi.fn().mockResolvedValue(res(200, broken)))).rejects.toThrow()
    }
  })

  it('creates with CSRF and accepts only a feed link', async () => {
    const request = vi.fn().mockResolvedValue(res(201, { url }))
    expect(await createFeed('c', request)).toBe(url)
    expect(request.mock.calls[0]).toEqual(['/api/v1/me/calendar-feed',
      { method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-Token': 'c' } }])
    expect(await createFeed('c', vi.fn().mockResolvedValue(res(401)))).toBe('denied')
    for (const broken of [{ url: 'javascript:alert(1)' }, { url: url + 'x' }, { url, extra: 1 }]) {
      await expect(createFeed('c', vi.fn().mockResolvedValue(res(201, broken)))).rejects.toThrow()
    }
  })

  it('revokes with CSRF', async () => {
    const request = vi.fn().mockResolvedValue(res(204))
    expect(await revokeFeed('c', request)).toBe('ok')
    expect(request.mock.calls[0][1]).toEqual({ method: 'DELETE', credentials: 'same-origin', headers: { 'X-CSRF-Token': 'c' } })
    expect(await revokeFeed('c', vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    await expect(revokeFeed('c', vi.fn().mockResolvedValue(res(503)))).rejects.toThrow()
  })
})
