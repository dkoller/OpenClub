import { describe, expect, it, vi } from 'vitest'
import { readPublicClubName } from './publicClub'

const res = (status: number, body?: unknown) =>
  ({ ok: status >= 200 && status < 300, status, json: async () => body }) as Response

describe('readPublicClubName', () => {
  it('reads only the name, without credentials', async () => {
    const request = vi.fn().mockResolvedValue(res(200, { name: 'FC Musterfeld' }))
    expect(await readPublicClubName(request)).toBe('FC Musterfeld')
    expect(request).toHaveBeenCalledWith('/api/v1/public/club', { credentials: 'omit', cache: 'no-store' })
  })

  it('is null without a club or for any other answer', async () => {
    for (const answer of [res(404, { code: 'not_found' }), res(503), res(200, { name: 'X', id: 'y' }), res(200, { name: '' }), res(200, ['x'])]) {
      expect(await readPublicClubName(vi.fn().mockResolvedValue(answer))).toBeNull()
    }
  })
})
