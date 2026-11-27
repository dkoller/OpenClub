import { describe, expect, it, vi } from 'vitest'
import { createAbsence, deleteAbsence, parseAbsences, readAbsences } from './absences'

const list = { items: [{ id: 'a1', memberId: 'm1', firstName: 'Lea', from: '2026-12-01', to: '2026-12-05', note: 'school camp' }],
  people: [{ id: 'm1', firstName: 'Lea' }] }
const res = (status: number, body?: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body })

describe('absences', () => {
  it('accepts exactly the documented list', () => {
    expect(parseAbsences(list)).toEqual(list)
    const item = list.items[0]
    for (const broken of [{ ...list, extra: 1 }, { ...list, items: [{ ...item, reason: 'ill' }] }, { ...list, items: [{ ...item, note: 3 }] },
      { ...list, items: [{ ...item, from: '1.12.2026' }] }, { ...list, people: [{ id: 'm1' }] }, null]) {
      expect(() => parseAbsences(broken)).toThrow()
    }
  })

  it('reads, creates and deletes with the documented requests', async () => {
    const read = vi.fn().mockResolvedValue(res(200, list))
    expect(await readAbsences(read)).toEqual(list)
    expect(read.mock.calls[0]).toEqual(['/api/v1/me/absences', { credentials: 'same-origin', cache: 'no-store' }])
    expect(await readAbsences(vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    const post = vi.fn().mockResolvedValue(res(201, { id: 'a2' }))
    expect(await createAbsence('m1', '2026-12-01', '2026-12-02', 'c', post)).toBe('ok')
    expect(post.mock.calls[0]).toEqual(['/api/v1/me/absences', { method: 'POST', credentials: 'same-origin',
      headers: { 'X-CSRF-Token': 'c', 'Content-Type': 'application/json' },
      body: '{"memberId":"m1","from":"2026-12-01","to":"2026-12-02"}' }])
    const noted = vi.fn().mockResolvedValue(res(201, { id: 'a3' }))
    await createAbsence('m1', '2026-12-01', '2026-12-02', 'c', noted, '  camp ')
    expect(noted.mock.calls[0][1].body).toBe('{"memberId":"m1","from":"2026-12-01","to":"2026-12-02","note":"camp"}')
    expect(await createAbsence('m1', 'x', 'y', 'c', vi.fn().mockResolvedValue(res(400)))).toBe('invalid')
    expect(await createAbsence('m1', 'x', 'y', 'c', vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    const del = vi.fn().mockResolvedValue(res(204))
    expect(await deleteAbsence('a/1', 'c', del)).toBe('ok')
    expect(del.mock.calls[0]).toEqual(['/api/v1/absences/a%2F1', { method: 'DELETE', credentials: 'same-origin', headers: { 'X-CSRF-Token': 'c' } }])
    expect(await deleteAbsence('a1', 'c', vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    await expect(deleteAbsence('a1', 'c', vi.fn().mockResolvedValue(res(500)))).rejects.toThrow()
  })
})
