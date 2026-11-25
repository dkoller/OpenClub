import { describe, expect, it, vi } from 'vitest'
import { readHelperDuty, readMyHelperDuty, saveHelperDuty } from './helperDuty'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

describe('helper duty', () => {
  it('reads the own count and the overview strictly', async () => {
    const mine = { quota: 3, done: 1, seasonFrom: '2026-08-01', seasonTo: '2027-07-31' }
    expect(await readMyHelperDuty(vi.fn().mockResolvedValue(json(mine)))).toEqual(mine)
    await expect(readMyHelperDuty(vi.fn().mockResolvedValue(json({ ...mine, done: -1 })))).rejects.toThrow()
    const overview = { quota: 3, seasonStartMonth: 8, seasonFrom: 'a', seasonTo: 'b', items: [{ memberId: 'm', displayName: 'Ada Example', done: 1, owed: 2 }] }
    expect(await readHelperDuty(vi.fn().mockResolvedValue(json(overview)))).toEqual(overview)
    expect(await readHelperDuty(vi.fn().mockResolvedValue(json({}, 403)))).toBe('denied')
    await expect(readHelperDuty(vi.fn().mockResolvedValue(json({ ...overview, items: [{ ...overview.items[0], phone: '1' }] })))).rejects.toThrow()
  })

  it('saves the quota and month', async () => {
    const request = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    expect(await saveHelperDuty(3, 8, 'tok', request)).toBe('ok')
    expect(request.mock.calls[0][1]).toMatchObject({ method: 'PUT', body: '{"quota":3,"seasonStartMonth":8}' })
    expect(await saveHelperDuty(99, 8, 't', vi.fn().mockResolvedValue(json({}, 400)))).toBe('invalid')
  })
})
