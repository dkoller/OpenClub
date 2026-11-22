import { describe, expect, it, vi } from 'vitest'
import {
  createPeriod, deletePeriod, formatFrancs, parseFrancs, qrBillPath, readFees, readMyFees, readPaymentDetails, readPeriods, removeFee,
  savePaymentDetails, setFee, today,
} from './fees'

const res = (status: number, body?: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body })
const period = { id: 'p1', name: 'Season', dueDate: '2026-12-31', amountCents: 15000, members: 3, paid: 1, totalCents: 45000, paidCents: 15000 }
const fee = { memberId: 'm1', firstName: 'Ada', lastName: 'Example', archived: false, amountCents: null, paidOn: null, reference: null }
const mine = { periodId: 'p1', periodName: 'Season', dueDate: '2026-12-31', amountCents: 8000, paidOn: '2026-11-01', for: { id: 'k1', firstName: 'Kim' }, qrBill: false }

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
    // 1.26.0: the reference per member and the QR bill flag per own fee are required.
    expect(await readFees('p', 'all', vi.fn().mockResolvedValue(res(200, { items: [{ ...fee, reference: 'RF53 P1M1' }] }))))
      .toEqual([{ ...fee, reference: 'RF53 P1M1' }])
    const { reference: _reference, ...withoutReference } = fee
    await expect(readFees('p', 'all', vi.fn().mockResolvedValue(res(200, { items: [withoutReference] })))).rejects.toThrow()
    await expect(readFees('p', 'all', vi.fn().mockResolvedValue(res(200, { items: [{ ...fee, reference: 5 }] })))).rejects.toThrow()
    const { qrBill: _qrBill, ...withoutFlag } = mine
    await expect(readMyFees(vi.fn().mockResolvedValue(res(200, { items: [withoutFlag] })))).rejects.toThrow()
    await expect(readMyFees(vi.fn().mockResolvedValue(res(200, { items: [{ ...mine, qrBill: 'yes' }] })))).rejects.toThrow()
  })

  it('reads and replaces the payment details and links the QR bill', async () => {
    const details = { iban: 'CH9300762011623852957', qrIban: false, holderName: 'FC Beispiel', street: null, buildingNumber: null,
      postalCode: '8000', town: 'Zürich', country: 'CH' as const }
    const read = vi.fn().mockResolvedValue(res(200, details))
    expect(await readPaymentDetails(read)).toEqual(details)
    expect(read.mock.calls[0][0]).toBe('/api/v1/club/payment-details')
    expect(await readPaymentDetails(vi.fn().mockResolvedValue(res(404)))).toBe('none')
    expect(await readPaymentDetails(vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    for (const broken of [{ ...details, country: 'DE' }, { ...details, extra: 1 }, { ...details, street: 1 }]) {
      await expect(readPaymentDetails(vi.fn().mockResolvedValue(res(200, broken)))).rejects.toThrow()
    }
    await expect(readPaymentDetails(vi.fn().mockResolvedValue(res(500)))).rejects.toThrow()
    const put = vi.fn().mockResolvedValue(res(204))
    expect(await savePaymentDetails({ iban: ' CH93 0076 2011 6238 5295 7 ', holderName: ' FC Beispiel ', street: ' ', buildingNumber: '',
      postalCode: '8000', town: ' Zürich', country: 'CH' }, 'c', put)).toBe('ok')
    expect(put.mock.calls[0][0]).toBe('/api/v1/club/payment-details')
    expect(put.mock.calls[0][1]).toMatchObject({ method: 'PUT', headers: { 'X-CSRF-Token': 'c', 'Content-Type': 'application/json' } })
    expect(JSON.parse(put.mock.calls[0][1].body)).toEqual({ iban: 'CH93 0076 2011 6238 5295 7', holderName: 'FC Beispiel', street: null,
      buildingNumber: null, postalCode: '8000', town: 'Zürich', country: 'CH' })
    expect(await savePaymentDetails({ ...details, street: 'Seeweg', buildingNumber: '3' }, 'c', vi.fn().mockResolvedValue(res(400)))).toBe('invalid')
    expect(qrBillPath('p/1', 'm 1', 'fr')).toBe('/api/v1/me/fees/p%2F1/m%201/qr-bill?lang=fr')
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
