import { describe, expect, it, vi } from 'vitest'
import { createPeriod, deletePeriod, formatFrancs, parseFrancs, readFees, readMyFees, readPeriods, removeFee, setFee, today } from './fees'

const res = (status: number, body?: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body })
const period = { id: 'p1', name: 'Season', dueDate: '2026-12-31', amountCents: 15000, members: 3, paid: 1, totalCents: 45000, paidCents: 15000 }
const fee = { memberId: 'm1', firstName: 'Ada', lastName: 'Example', archived: false, amountCents: null, paidOn: null }
const mine = { periodId: 'p1', periodName: 'Season', dueDate: '2026-12-31', amountCents: 8000, paidOn: '2026-11-01', for: { id: 'k1', firstName: 'Kim' } }

describe('fees client', () => {
  it('reads periods, fees and own fees strictly', async () => {
    expect(await readPeriods(vi.fn().mockResolvedValue(res(200, { items: [period] })))).toEqual([period])
    const request = vi.fn().mockResolvedValue(res(200, { items: [fee] }))
    expect(await readFees('p/1', 'excluded', request)).toEqual([fee])
    expect(request.mock.calls[0][0]).toBe('/api/v1/fee-periods/p%2F1/fees?status=excluded')
    expect(await readMyFees(vi.fn().mockResolvedValue(res(200, { items: [mine] })))).toEqual([mine])
    expect(await readPeriods(vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    for (const broken of [{ ...period, extra: 1 }, { ...period, amountCents: 1.5 }, { ...period, dueDate: '31.12.2026' }]) {
      await expect(readPeriods(vi.fn().mockResolvedValue(res(200, { items: [broken] })))).rejects.toThrow()
    }
    await expect(readMyFees(vi.fn().mockResolvedValue(res(200, { items: [{ ...mine, for: { id: 'k1' } }] })))).rejects.toThrow()
    await expect(readFees('p', 'all', vi.fn().mockResolvedValue(res(200, { items: [{ ...fee, archived: 'no' }] })))).rejects.toThrow()
  })

  it('writes with CSRF and maps statuses', async () => {
    const request = vi.fn().mockResolvedValue(res(201, { id: 'p1' }))
    expect(await createPeriod(' Season ', '2026-12-31', 15000, 'c', request)).toBe('ok')
    expect(request.mock.calls[0][1]).toMatchObject({ method: 'POST', body: '{"name":"Season","dueDate":"2026-12-31","amountCents":15000}',
      headers: { 'X-CSRF-Token': 'c' } })
    const put = vi.fn().mockResolvedValue(res(204))
    await setFee('p1', 'm 1', 8000, null, 'c', put)
    expect(put.mock.calls[0][0]).toBe('/api/v1/fee-periods/p1/fees/m%201')
    expect(put.mock.calls[0][1].body).toBe('{"amountCents":8000,"paidOn":null}')
    for (const [status, outcome] of [[400, 'invalid'], [403, 'denied'], [404, 'not-found'], [409, 'conflict']] as const) {
      expect(await deletePeriod('p1', 'Season', 'c', vi.fn().mockResolvedValue(res(status)))).toBe(outcome)
    }
    await expect(removeFee('p1', 'm1', 'c', vi.fn().mockResolvedValue(res(500)))).rejects.toThrow()
  })

  it('parses and formats francs', () => {
    expect(parseFrancs('150')).toBe(15000)
    expect(parseFrancs('150.5')).toBe(15050)
    expect(parseFrancs('150,05')).toBe(15005)
    expect(parseFrancs(' 0 ')).toBe(0)
    for (const bad of ['', '-1', '1.234', 'abc', '100001', '1e3']) expect(parseFrancs(bad)).toBeNull()
    expect(parseFrancs('100000')).toBe(10_000_000)
    expect(formatFrancs(15050, 'en')).toMatch(/150\.50/)
    expect(today(new Date(2026, 10, 19))).toBe('2026-11-19')
  })
})
