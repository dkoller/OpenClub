import { describe, expect, it, vi } from 'vitest'
import { deleteAnnouncement, postAnnouncement, readAnnouncements, replaceAnnouncement } from './announcements'

const res = (status: number, body?: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body })
const item = { id: 'a1', teamId: null, teamName: null, title: 'Hi', body: 'Line\nTwo', showUntil: '2026-12-31',
  createdAt: '2026-11-19T18:00:00Z', canManage: false }

describe('announcements client', () => {
  it('reads strictly', async () => {
    const body = { items: [item], audiences: [{ teamId: 't1', teamName: 'U12' }] }
    expect(await readAnnouncements(vi.fn().mockResolvedValue(res(200, body)))).toEqual(body)
    expect(await readAnnouncements(vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    for (const broken of [{ items: [{ ...item, html: '<b>' }], audiences: [] }, { items: [{ ...item, canManage: 'yes' }], audiences: [] },
      { items: [], audiences: [{ teamId: 't1' }] }, { items: [] }]) {
      await expect(readAnnouncements(vi.fn().mockResolvedValue(res(200, broken)))).rejects.toThrow()
    }
  })

  it('posts, edits and deletes with CSRF and trimmed text', async () => {
    const request = vi.fn().mockResolvedValue(res(201, { id: 'a1' }))
    expect(await postAnnouncement(null, { title: ' Hi ', body: ' Text ', showUntil: '' }, 'c', request)).toBe('ok')
    expect(request.mock.calls[0][1]).toMatchObject({ method: 'POST', body: '{"teamId":null,"title":"Hi","body":"Text"}',
      headers: { 'X-CSRF-Token': 'c' } })
    const put = vi.fn().mockResolvedValue(res(204))
    await replaceAnnouncement('a/1', { title: 'T', body: 'B', showUntil: '2026-12-31' }, 'c', put)
    expect(put.mock.calls[0][0]).toBe('/api/v1/announcements/a%2F1')
    expect(put.mock.calls[0][1].body).toBe('{"title":"T","body":"B","showUntil":"2026-12-31"}')
    for (const [status, outcome] of [[400, 'invalid'], [403, 'denied'], [404, 'not-found']] as const) {
      expect(await deleteAnnouncement('a1', 'c', vi.fn().mockResolvedValue(res(status)))).toBe(outcome)
    }
    await expect(deleteAnnouncement('a1', 'c', vi.fn().mockResolvedValue(res(500)))).rejects.toThrow()
  })
})
