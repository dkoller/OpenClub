import { describe, expect, it, vi } from 'vitest'
import { addGuardian, listDependents, listGuardians, listMinorsWithoutGuardian, readDependent, removeGuardian, replaceDependentContacts } from './guardians'

const res = (status: number, body?: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body })
const csrf = 'c'.repeat(43)
const dependent = { id: 'm', firstName: 'Kid', lastName: 'Minor', dateOfBirth: '2015-03-04', email: null, phone: '+41', isPlayer: true, teams: ['U12'] }

describe('guardian requests', () => {
  it('lists guardians for editors and maps 404 and denial', async () => {
    const ok = vi.fn().mockResolvedValue(res(200, { items: [{ id: 'g', firstName: 'G', lastName: 'One' }] }))
    expect(await listGuardians('a/b', ok)).toEqual([{ id: 'g', firstName: 'G', lastName: 'One' }])
    expect(ok.mock.calls[0]).toEqual(['/api/v1/members/a%2Fb/guardians', { credentials: 'same-origin', cache: 'no-store' }])
    expect(await listGuardians('m', vi.fn().mockResolvedValue(res(404)))).toBe('not-found')
    expect(await listGuardians('m', vi.fn().mockResolvedValue(res(403)))).toBe('denied')
  })

  it('adds and removes with CSRF and maps every outcome', async () => {
    const ok = vi.fn().mockResolvedValue(res(204))
    expect(await addGuardian('m', 'g', csrf, ok)).toBe('ok')
    expect(ok.mock.calls[0][1]).toMatchObject({ method: 'POST', body: '{"guardianMemberId":"g"}', headers: { 'X-CSRF-Token': csrf } })
    expect(await removeGuardian('m', 'g/x', csrf, ok)).toBe('ok')
    expect(ok.mock.calls[1][0]).toBe('/api/v1/members/m/guardians/g%2Fx')
    for (const [status, outcome] of [[403, 'denied'], [400, 'invalid'], [404, 'not-found'], [409, 'conflict']] as const) {
      expect(await addGuardian('m', 'g', csrf, vi.fn().mockResolvedValue(res(status)))).toBe(outcome)
    }
    await expect(addGuardian('m', 'g', csrf, vi.fn().mockResolvedValue(res(503)))).rejects.toThrow()
  })

  it('reads my dependents strictly and replaces only their contacts', async () => {
    expect(await listDependents(undefined, vi.fn().mockResolvedValue(res(200, { items: [] })))).toEqual([])
    expect(await listDependents(undefined, vi.fn().mockResolvedValue(res(401)))).toBe('denied')
    expect(await readDependent('m', vi.fn().mockResolvedValue(res(200, dependent)))).toEqual(dependent)
    expect(await readDependent('m', vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    for (const bad of [{ ...dependent, gender: 'x' }, { ...dependent, teams: [1] }, { ...dependent, email: 3 }, null]) {
      await expect(readDependent('m', vi.fn().mockResolvedValue(res(200, bad)))).rejects.toThrow()
    }
    const put = vi.fn().mockResolvedValue(res(204))
    expect(await replaceDependentContacts('m', ' k@example.invalid ', '', csrf, put)).toBe('ok')
    expect(put.mock.calls[0]).toEqual(['/api/v1/me/dependents/m/contacts', expect.objectContaining({
      method: 'PUT', body: '{"email":"k@example.invalid"}' })])
  })
})

describe('minors without a guardian', () => {
  it('lists them for editors and fails closed on denial', async () => {
    const items = [{ id: 'k1', firstName: 'Mila', lastName: 'Baumann' }]
    const request = vi.fn().mockResolvedValue(res(200, { items }))
    expect(await listMinorsWithoutGuardian(undefined, request)).toEqual(items)
    expect(request.mock.calls[0][0]).toBe('/api/v1/guardians/missing')
    expect(await listMinorsWithoutGuardian(undefined, vi.fn().mockResolvedValue(res(403)))).toBe('denied')
  })
})
