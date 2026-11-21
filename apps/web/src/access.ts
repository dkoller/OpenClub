import { fetchArchivedPage } from './members'
import { probeGrantAccess } from './grants'
import { readPeriods } from './fees'
import { readTeams } from './teams'
import { readItems } from './api'

/**
 * Which parts of the app the signed-in member can use, so navigation shows only those. It asks existing read-only
 * endpoints once after sign-in (no endpoint lists a member's rights); a failed probe hides the entry. The server
 * still checks every request: this only avoids showing pages that would be refused. Only a denial (401/403) hides an
 * entry; when a probe fails for another reason the entry stays, so its page can show the error and a retry.
 */
export type Access = {
  /** VIEW_TEAMS or MANAGE_TEAMS, club-wide or for a team. */
  teams: boolean
  /** EDIT_MEMBERS: members, archive, guardians, invitations, import, join requests. */
  editor: boolean
  /** VIEW_MEMBER_DIRECTORY: the member search. */
  directory: boolean
  /** MANAGE_FEES. */
  fees: boolean
  /** MANAGE_PERMISSIONS: rights, roles, club settings. */
  permissions: boolean
}

export const noAccess: Access = { teams: false, editor: false, directory: false, fees: false, permissions: false }

const allowed = async (probe: () => Promise<boolean>) => { try { return await probe() } catch { return true } }

export async function loadAccess(request: typeof fetch = fetch): Promise<Access> {
  const [teams, editor, directory, fees, permissions] = await Promise.all([
    allowed(async () => await readTeams(request) !== 'denied'),
    allowed(async () => await fetchArchivedPage('', undefined, undefined, request) !== 'denied'),
    allowed(async () => await readItems('/api/v1/members?q=&limit=1', undefined, request) !== 'denied'),
    allowed(async () => await readPeriods(request) !== 'denied'),
    allowed(async () => await probeGrantAccess(request) === 'allowed'),
  ])
  return { teams, editor, directory, fees, permissions }
}

export const hasAdmin = (access: Access) => access.editor || access.directory || access.fees || access.permissions
