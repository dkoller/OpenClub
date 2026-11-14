import { describe, expect, it, vi } from 'vitest'
import { fetchHealth } from './health'

describe('fetchHealth', () => {
  it('accepts the API liveness response', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ status: 'ok' }) })
    expect(await fetchHealth(request)).toBe(true)
    expect(request).toHaveBeenCalledWith('/api/health')
  })

  it('rejects failed requests', async () => {
    const request = vi.fn().mockResolvedValue({ ok: false })
    expect(await fetchHealth(request)).toBe(false)
  })
})
