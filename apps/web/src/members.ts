import { readItems } from './api'

/** `teams` (names, since OpenAPI 1.37.0) comes only with the active member list. */
export type Member = { id: string; firstName: string; lastName: string; teams?: string[] }

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

export type ArchivedMember = Member & { archivedAt: string }

/** Archived members for editors (OpenAPI 1.5.0): names and archive time only. */
export function archivedPagePath(query: string, cursor?: string): string {
  return memberPagePath(query, cursor).replace('/api/v1/members?', '/api/v1/members/archived?')
}

export async function fetchArchivedPage(query: string, cursor?: string, signal?: AbortSignal,
  request: typeof fetch = fetch) {
  const page = await readItems<ArchivedMember>(archivedPagePath(query, cursor), signal, request)
  if (page !== 'denied' && !page.items.every(item => typeof item?.id === 'string' &&
      typeof item.firstName === 'string' && typeof item.lastName === 'string' && typeof item.archivedAt === 'string')) {
    throw new Error('Invalid archived page')
  }
  return page
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
