import { describe, expect, it, vi } from 'vitest'
import { readPublicNames } from './publicMembers'

const json = (status: number, body?: unknown) =>
  ({ ok: status >= 200 && status < 300, status, json: async () => body })

describe('public member names', () => {
  it('requests anonymously and uncached', async () => {
    const request = vi.fn().mockResolvedValue(json(200, [{ displayName: 'Ada E.' }]))
    expect(await readPublicNames(undefined, request)).toEqual([{ displayName: 'Ada E.' }])
    expect(request).toHaveBeenCalledWith('/api/v1/public/members', { credentials: 'omit', cache: 'no-store' })
  })

  it('rejects any entry carrying more than a display name', async () => {
    for (const body of [
      [{ displayName: 'Ada', email: 'ada@example.invalid' }],
      [{ displayName: 'Ada', id: 'm1' }],
      [{ name: 'Ada' }],
      [{ displayName: 7 }],
      { items: [] },
      null,
    ]) {
      await expect(readPublicNames(undefined, vi.fn().mockResolvedValue(json(200, body)))).rejects.toThrow()
    }
    await expect(readPublicNames(undefined, vi.fn().mockResolvedValue(json(503)))).rejects.toThrow()
  })
})
