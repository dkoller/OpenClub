import { describe, expect, it, vi } from 'vitest'
import { readItems, sendJson, writeJson } from './api'

const csrf = 'x'.repeat(43)

describe('readItems', () => {
  it('reads same-origin without caching and forwards the abort signal', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ items: [] }) })
    const signal = new AbortController().signal
    expect(await readItems('/api/v1/teams', signal, request)).toEqual({ items: [] })
    expect(request).toHaveBeenCalledWith('/api/v1/teams',
      { credentials: 'same-origin', cache: 'no-store', signal })
  })

  it('fails closed on 401 and 403', async () => {
    for (const status of [401, 403]) {
      const request = vi.fn().mockResolvedValue({ ok: false, status })
      expect(await readItems('/api/v1/members', undefined, request)).toBe('denied')
    }
  })

  it('rejects server errors and malformed bodies instead of rendering them', async () => {
    for (const response of [
      { ok: false, status: 500 },
      { ok: true, status: 200, json: async () => null },
      { ok: true, status: 200, json: async () => ({}) },
      { ok: true, status: 200, json: async () => ({ items: 'not-a-list' }) },
    ]) {
      await expect(readItems('/api/v1/members', undefined, vi.fn().mockResolvedValue(response)))
        .rejects.toThrow()
    }
  })
})

describe('writeJson', () => {
  it('sends the CSRF token and JSON body with same-origin credentials', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, status: 204 })
    expect(await writeJson('/api/v1/members/1', 'PATCH', csrf, { firstName: 'Ada' }, request)).toBe('ok')
    expect(request).toHaveBeenCalledWith('/api/v1/members/1', {
      method: 'PATCH',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
      body: '{"firstName":"Ada"}',
    })
  })

  it('omits the body when there is none', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, status: 204 })
    await writeJson('/api/v1/members/1/archive', 'POST', csrf, undefined, request)
    expect(request.mock.calls[0][1]).not.toHaveProperty('body')
  })

  it('distinguishes denial from other rejected writes', async () => {
    expect(await writeJson('/x', 'POST', csrf, {}, vi.fn().mockResolvedValue({ ok: false, status: 403 })))
      .toBe('denied')
    await expect(writeJson('/x', 'POST', csrf, {}, vi.fn().mockResolvedValue({ ok: false, status: 409 })))
      .rejects.toThrow()
  })
})

describe('sendJson', () => {
  it('reports each HTTP outcome separately', async () => {
    for (const [status, outcome] of [[200, 'ok'], [204, 'ok'], [401, 'unauthenticated'], [403, 'forbidden'], [409, 'conflict'], [400, 'failed'], [503, 'failed']] as const) {
      expect(await sendJson('/x', 'POST', csrf, {}, vi.fn().mockResolvedValue({ ok: status < 300, status }))).toBe(outcome)
    }
  })

  it('rejects on network errors so callers can show a retryable error', async () => {
    await expect(sendJson('/x', 'POST', csrf, {}, vi.fn().mockRejectedValue(new Error('offline')))).rejects.toThrow()
  })
})
