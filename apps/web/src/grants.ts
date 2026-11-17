import { readItems } from './api'

export type Scope = 'CLUB' | 'TEAM'
export type Capability = 'VIEW_MEMBER_DIRECTORY' | 'EDIT_MEMBERS' | 'VIEW_TEAMS' | 'MANAGE_TEAMS' | 'MANAGE_PERMISSIONS'
export type Grant = { capability: string; scope: Scope; teamId?: string }

export const capabilities: Capability[] = ['VIEW_MEMBER_DIRECTORY', 'EDIT_MEMBERS', 'VIEW_TEAMS', 'MANAGE_TEAMS', 'MANAGE_PERMISSIONS']
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
