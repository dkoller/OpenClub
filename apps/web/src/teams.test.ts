import { describe, expect, it, vi } from 'vitest'
import { changeAssignment, createTeam, deleteTeam, readRoster, readTeams, renameTeam, rosterPath } from './teams'

const csrf = 'x'.repeat(43)
const json = (status: number, body?: unknown) =>
  ({ ok: status >= 200 && status < 300, status, json: async () => body })

describe('team reads', () => {
  it('returns well-formed teams and fails closed on denial', async () => {
    const items = [{ id: 't1', name: 'Alpha', memberCount: 2 }]
    expect(await readTeams(vi.fn().mockResolvedValue(json(200, { items })))).toEqual(items)
    expect(await readTeams(vi.fn().mockResolvedValue(json(401)))).toBe('denied')
    expect(await readTeams(vi.fn().mockResolvedValue(json(403)))).toBe('denied')
  })

  it('rejects malformed teams instead of rendering them', async () => {
    for (const item of [{ id: 't1', name: 'Alpha' }, { id: 1, name: 'A', memberCount: 0 }, null, 'team']) {
      await expect(readTeams(vi.fn().mockResolvedValue(json(200, { items: [item] })))).rejects.toThrow()
    }
  })

  it('pages the roster with an encoded team id and optional cursor', async () => {
    expect(rosterPath('a/b')).toBe('/api/v1/teams/a%2Fb/members?limit=20')
    expect(rosterPath('t1', 'c')).toBe('/api/v1/teams/t1/members?limit=20&cursor=c')
    const request = vi.fn().mockResolvedValue(json(200, { items: [] }))
    expect(await readRoster('t1', undefined, undefined, request)).toEqual({ items: [] })
  })
})

describe('team writes', () => {
  it('keeps 401 and 403 apart so the UI can hide only the editor', async () => {
    for (const [status, outcome] of [[201, 'ok'], [204, 'ok'], [401, 'unauthenticated'], [403, 'forbidden'], [409, 'conflict'], [400, 'failed']] as const) {
      expect(await createTeam('Alpha', csrf, vi.fn().mockResolvedValue({ ok: status < 300, status }))).toBe(outcome)
    }
  })

  it('sends rename and confirmed delete with CSRF and the expected bodies', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, status: 204 })
    await renameTeam('t/1', 'Beta', csrf, request)
    await deleteTeam('t/1', 'Beta', csrf, request)
    expect(request.mock.calls[0]).toEqual(['/api/v1/teams/t%2F1', expect.objectContaining({
      method: 'PATCH', body: '{"name":"Beta"}',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf } })])
    expect(request.mock.calls[1][1]).toMatchObject({ method: 'DELETE', body: '{"name":"Beta"}' })
  })

  it('assigns and unassigns without a body and fails closed on denial', async () => {
    const request = vi.fn().mockResolvedValue({ ok: true, status: 204 })
    expect(await changeAssignment('t1', 'm/1', 'POST', csrf, request)).toBe('ok')
    expect(request).toHaveBeenCalledWith('/api/v1/teams/t1/members/m%2F1',
      { method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-Token': csrf } })
    expect(await changeAssignment('t1', 'm1', 'DELETE', csrf, vi.fn().mockResolvedValue({ ok: false, status: 403 })))
      .toBe('denied')
    await expect(changeAssignment('t1', 'm1', 'DELETE', csrf, vi.fn().mockResolvedValue({ ok: false, status: 409 })))
      .rejects.toThrow()
  })
})
