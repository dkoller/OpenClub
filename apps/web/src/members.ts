import { readItems } from './api'

export type Member = { id: string; firstName: string; lastName: string }

export type MemberDraft = {
  firstName: string; lastName: string; dateOfBirth: string
  email: string; phone: string; isPlayer: boolean; gender: string
}

/** One bounded directory page; `cursor` continues a previous page. */
export function memberPagePath(query: string, cursor?: string): string {
  const args = new URLSearchParams({ q: query, limit: '20' })
  if (cursor) args.set('cursor', cursor)
  return '/api/v1/members?' + args
}

export function fetchMemberPage(query: string, cursor?: string, signal?: AbortSignal,
  request: typeof fetch = fetch) {
  return readItems<Member>(memberPagePath(query, cursor), signal, request)
}

/**
 * Create request body. Empty optional contacts are omitted so the server applies
 * its adult/minor rules; gender is sent only for players and only when non-blank.
 */
export function memberCreateBody(draft: MemberDraft) {
  const gender = draft.gender.trim()
  return {
    firstName: draft.firstName, lastName: draft.lastName, dateOfBirth: draft.dateOfBirth,
    ...(draft.email ? { email: draft.email } : {}),
    ...(draft.phone ? { phone: draft.phone } : {}),
    isPlayer: draft.isPlayer,
    ...(draft.isPlayer && gender ? { gender } : {}),
  }
}
