import { readItems } from './api'
import type { Language } from './language'

export type Scope = 'CLUB' | 'TEAM'
export type Capability = 'VIEW_MEMBER_DIRECTORY' | 'EDIT_MEMBERS' | 'VIEW_TEAMS' | 'MANAGE_TEAMS' | 'MANAGE_PERMISSIONS' | 'MANAGE_FEES' | 'POST_ANNOUNCEMENTS' | 'MANAGE_VENUES'
export type Grant = { capability: string; scope: Scope; teamId?: string }

export const capabilities: Capability[] = ['VIEW_MEMBER_DIRECTORY', 'EDIT_MEMBERS', 'VIEW_TEAMS', 'MANAGE_TEAMS', 'MANAGE_PERMISSIONS', 'MANAGE_FEES', 'POST_ANNOUNCEMENTS', 'MANAGE_VENUES']
const teamCapabilities: Capability[] = ['VIEW_TEAMS', 'MANAGE_TEAMS']

/** Only team capabilities can be delegated to one team; the server enforces the same rule. */
export function capabilityChoices(scope: Scope): Capability[] {
  return scope === 'TEAM' ? teamCapabilities : capabilities
}

/** Keeps the selected capability valid when the scope changes. */
export function capabilityForScope(scope: Scope, current: Capability): Capability {
  return capabilityChoices(scope).includes(current) ? current : 'VIEW_TEAMS'
}

export function grantChange(capability: Capability, scope: Scope, teamId: string): Grant {
  return { capability, scope, ...(scope === 'TEAM' ? { teamId } : {}) }
}

export function fetchGrants(memberId: string, signal?: AbortSignal, request: typeof fetch = fetch) {
  return readItems<Grant>('/api/v1/grants?memberId=' + encodeURIComponent(memberId), signal, request)
}

/** Probes grant-management access with a nil member ID; 'denied' for 401/403, throws on other failures. */
export async function probeGrantAccess(request: typeof fetch = fetch): Promise<'allowed' | 'denied'> {
  const response = await request('/api/v1/grants?memberId=00000000-0000-0000-0000-000000000000',
    { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Grant access unavailable')
  return 'allowed'
}

export type OverviewGrant = Grant & { teamName?: string }
export type GrantHolder = { memberId: string; firstName: string; lastName: string; grants: OverviewGrant[] }

/** Everyone who holds a permission, for MANAGE_PERMISSIONS holders (OpenAPI 1.36.0). */
export function fetchGrantOverview(signal?: AbortSignal, request: typeof fetch = fetch) {
  return readItems<GrantHolder>('/api/v1/grants/overview', signal, request)
}

const capabilityWords: Record<Language, Record<Capability, string>> = {
  de: { VIEW_MEMBER_DIRECTORY: 'Mitgliederliste ansehen', EDIT_MEMBERS: 'Mitglieder bearbeiten', VIEW_TEAMS: 'Teams ansehen', MANAGE_TEAMS: 'Teams leiten', MANAGE_PERMISSIONS: 'Berechtigungen verwalten', MANAGE_FEES: 'Beiträge verwalten', POST_ANNOUNCEMENTS: 'Mitteilungen veröffentlichen', MANAGE_VENUES: 'Plätze und Hallen verwalten' },
  en: { VIEW_MEMBER_DIRECTORY: 'See the member list', EDIT_MEMBERS: 'Edit members', VIEW_TEAMS: 'See teams', MANAGE_TEAMS: 'Run teams', MANAGE_PERMISSIONS: 'Manage permissions', MANAGE_FEES: 'Manage fees', POST_ANNOUNCEMENTS: 'Post announcements', MANAGE_VENUES: 'Manage fields and halls' },
  fr: { VIEW_MEMBER_DIRECTORY: 'Voir la liste des membres', EDIT_MEMBERS: 'Modifier les membres', VIEW_TEAMS: 'Voir les équipes', MANAGE_TEAMS: 'Diriger les équipes', MANAGE_PERMISSIONS: 'Gérer les autorisations', MANAGE_FEES: 'Gérer les cotisations', POST_ANNOUNCEMENTS: 'Publier des annonces', MANAGE_VENUES: 'Gérer terrains et salles' },
  it: { VIEW_MEMBER_DIRECTORY: 'Vedere l’elenco dei membri', EDIT_MEMBERS: 'Modificare i membri', VIEW_TEAMS: 'Vedere le squadre', MANAGE_TEAMS: 'Dirigere le squadre', MANAGE_PERMISSIONS: 'Gestire i permessi', MANAGE_FEES: 'Gestire le quote', POST_ANNOUNCEMENTS: 'Pubblicare annunci', MANAGE_VENUES: 'Gestire campi e palestre' },
}

/** A permission in plain words; an unknown code is shown as it is rather than hidden. */
export function capabilityLabel(capability: string, language: Language): string {
  return (capabilityWords[language] as Record<string, string>)[capability] ?? capability
}

/** "Run teams: Juniors U12" for a team grant, the plain label for a club-wide one. */
export function grantLabel(grant: Grant, language: Language, teamName?: string): string {
  const label = capabilityLabel(grant.capability, language)
  return grant.scope === 'TEAM' && teamName ? label + ': ' + teamName : label
}
