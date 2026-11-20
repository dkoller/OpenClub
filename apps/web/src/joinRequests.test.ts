import { describe, expect, it, vi } from 'vitest'
import { acceptJoinRequest, declineJoinRequest, readJoinForm, readJoinRequests, readJoinSettings, setJoinOpen, submitJoinRequest } from './joinRequests'

const res = (status: number, body?: unknown) => ({ ok: status >= 200 && status < 300, status, json: async () => body })
const application = { kind: 'SELF' as const, firstName: 'Ada', lastName: 'Example', dateOfBirth: '1990-01-01', email: 'a@example.invalid',
  phone: '+41', parentFirstName: null, parentLastName: null, teamId: null, message: null }
const item = { id: 'r1', createdAt: '2026-11-20T08:00:00Z', kind: 'CHILD', firstName: 'Lea', lastName: 'B', dateOfBirth: '2017-01-01',
  email: 'p@example.invalid', phone: '+41', parentFirstName: 'Pat', parentLastName: 'B', teamName: null, message: null }

describe('join requests', () => {
  it('reads the public form strictly', async () => {
    expect(await readJoinForm(vi.fn().mockResolvedValue(res(200, { open: true, teams: [{ id: 't', name: 'U12' }] }))))
      .toEqual({ open: true, teams: [{ id: 't', name: 'U12' }] })
    await expect(readJoinForm(vi.fn().mockResolvedValue(res(200, { open: true, teams: [], extra: 1 })))).rejects.toThrow()
  })

  it('submits with the privacy flag and an empty honeypot, and maps every outcome', async () => {
    const request = vi.fn().mockResolvedValue(res(202))
    expect(await submitJoinRequest(application, request)).toBe('ok')
    expect(request.mock.calls[0]).toEqual(['/api/v1/public/join-requests', { method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...application, acceptedPrivacyNotice: true, website: '' }) }])
    for (const [status, body, outcome] of [[400, undefined, 'invalid'], [429, undefined, 'limited'], [409, { code: 'full' }, 'full'],
      [409, { code: 'closed' }, 'closed']] as const) {
      expect(await submitJoinRequest(application, vi.fn().mockResolvedValue(res(status, body)))).toBe(outcome)
    }
    await expect(submitJoinRequest(application, vi.fn().mockResolvedValue(res(500)))).rejects.toThrow()
  })

  it('handles the switch, the list, accepting and declining for editors', async () => {
    expect(await readJoinSettings(vi.fn().mockResolvedValue(res(200, { open: false })))).toBe(false)
    expect(await readJoinSettings(vi.fn().mockResolvedValue(res(403)))).toBe('denied')
    const put = vi.fn().mockResolvedValue(res(204))
    expect(await setJoinOpen(true, 'c', put)).toBe('ok')
    expect(put.mock.calls[0][1]).toMatchObject({ method: 'PUT', body: '{"open":true}', headers: { 'X-CSRF-Token': 'c' } })
    expect(await readJoinRequests(vi.fn().mockResolvedValue(res(200, { items: [item] })))).toEqual([item])
    await expect(readJoinRequests(vi.fn().mockResolvedValue(res(200, { items: [{ ...item, ip: '1.2.3.4' }] })))).rejects.toThrow()
    const post = vi.fn().mockResolvedValue(res(201, { memberId: 'm' }))
    expect(await acceptJoinRequest('r/1', 'c', post)).toBe('ok')
    expect(post.mock.calls[0][0]).toBe('/api/v1/join-requests/r%2F1/accept')
    expect(await acceptJoinRequest('r1', 'c', vi.fn().mockResolvedValue(res(409)))).toBe('rejected')
    expect(await acceptJoinRequest('r1', 'c', vi.fn().mockResolvedValue(res(404)))).toBe('gone')
    expect(await declineJoinRequest('r1', 'c', vi.fn().mockResolvedValue(res(204)))).toBe('ok')
    expect(await declineJoinRequest('r1', 'c', vi.fn().mockResolvedValue(res(403)))).toBe('denied')
  })
})
