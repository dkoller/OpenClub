import { describe, expect, it, vi } from 'vitest'
import { memberListExportPath, uploadMemberList } from './memberList'

const res = (status: number, body?: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body })
const result = {
  rows: [{ line: 2, firstName: 'Lea', lastName: 'Brunner', status: 'OK', problems: [] },
    { line: 3, firstName: 'Ann', lastName: 'X', status: 'INVALID', problems: ['ADULT_CONTACTS'] }],
  counts: { ok: 1, duplicate: 0, invalid: 1 }, created: null,
}

describe('member list client', () => {
  it('links the export in the chosen language', () => {
    expect(memberListExportPath('fr')).toBe('/api/v1/members/export.csv?lang=fr')
  })

  it('uploads the raw file with CSRF and parses the result strictly', async () => {
    const file = new Blob(['Vorname;Nachname;Geburtsdatum\n'], { type: 'text/csv' })
    const request = vi.fn().mockResolvedValue(res(200, result))
    expect(await uploadMemberList(file, 'preview', 'c', request)).toEqual(result)
    expect(request.mock.calls[0][0]).toBe('/api/v1/members/import?mode=preview')
    expect(request.mock.calls[0][1]).toMatchObject({ method: 'POST', body: file,
      headers: { 'X-CSRF-Token': 'c', 'Content-Type': 'text/csv' } })
    expect(await uploadMemberList(file, 'commit', 'c', vi.fn().mockResolvedValue(res(400)))).toBe('unreadable')
    expect(await uploadMemberList(file, 'commit', 'c', vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    await expect(uploadMemberList(file, 'commit', 'c', vi.fn().mockResolvedValue(res(503)))).rejects.toThrow()
    for (const broken of [{ ...result, extra: 1 }, { ...result, rows: [{ ...result.rows[0], status: 'MAYBE' }] },
      { ...result, rows: [{ ...result.rows[1], problems: ['UNKNOWN'] }] }, { ...result, created: -1 }]) {
      await expect(uploadMemberList(file, 'preview', 'c', vi.fn().mockResolvedValue(res(200, broken)))).rejects.toThrow()
    }
  })
})
