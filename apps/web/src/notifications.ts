/** The member's own email notification settings (OpenAPI 1.19.0; helpersWanted since 1.24.0, answerReminders since 1.25.0, squadCalls since 1.28.0, teamMessages since 1.31.0). */
export type NotificationLanguage = 'de' | 'en' | 'fr' | 'it'
export type NotificationSettings = {
  eventChanges: boolean; newEvents: boolean; announcements: boolean; shiftReminders: boolean; helpersWanted: boolean; answerReminders: boolean; squadCalls: boolean; teamMessages: boolean
  language: NotificationLanguage
}
export type NotificationStatus = NotificationSettings & { emailEnabled: boolean; hasEmail: boolean }

const path = '/api/v1/me/notifications'
const switches = ['eventChanges', 'newEvents', 'announcements', 'shiftReminders', 'helpersWanted', 'answerReminders', 'squadCalls', 'teamMessages'] as const
const languages = ['de', 'en', 'fr', 'it']

export function parseNotificationStatus(body: unknown): NotificationStatus {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) throw new Error('Invalid settings')
  const value = body as Record<string, unknown>
  const keys = [...switches, 'language', 'emailEnabled', 'hasEmail']
  const valid = Object.keys(value).length === keys.length && keys.every(key => key in value) &&
    [...switches, 'emailEnabled', 'hasEmail'].every(key => typeof value[key] === 'boolean') &&
    typeof value.language === 'string' && languages.includes(value.language)
  if (!valid) throw new Error('Invalid settings')
  return value as NotificationStatus
}

export async function readNotifications(request: typeof fetch = fetch): Promise<NotificationStatus | 'denied'> {
  const response = await request(path, { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Settings unavailable')
  return parseNotificationStatus(await response.json())
}

export async function saveNotifications(settings: NotificationSettings, csrfToken: string,
  request: typeof fetch = fetch): Promise<'ok' | 'invalid' | 'denied'> {
  const body: NotificationSettings = {
    eventChanges: settings.eventChanges, newEvents: settings.newEvents, announcements: settings.announcements,
    shiftReminders: settings.shiftReminders, helpersWanted: settings.helpersWanted,
    answerReminders: settings.answerReminders, squadCalls: settings.squadCalls, teamMessages: settings.teamMessages, language: settings.language,
  }
  const response = await request(path, {
    method: 'PUT', credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken, 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  })
  if (response.ok) return 'ok'
  if (response.status === 400) return 'invalid'
  if (response.status === 401 || response.status === 403) return 'denied'
  throw new Error('Request failed')
}
