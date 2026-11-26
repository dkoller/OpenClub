/** Push notifications on this device (OpenAPI 1.33.0). The push is empty; the service worker fetches the text. */
export type PushStatus = { enabled: boolean; publicKey: string | null; devices: number }

export async function readPushStatus(request: typeof fetch = fetch): Promise<PushStatus | 'denied'> {
  const response = await request('/api/v1/me/push', { credentials: 'same-origin', cache: 'no-store' })
  if (response.status === 401 || response.status === 403) return 'denied'
  if (!response.ok) throw new Error('Push unavailable')
  const body: unknown = await response.json()
  if (typeof body !== 'object' || body === null || Object.keys(body).length !== 3) throw new Error('Invalid push status')
  const value = body as Record<string, unknown>
  if (typeof value.enabled !== 'boolean' || !(value.publicKey === null || typeof value.publicKey === 'string') ||
    typeof value.devices !== 'number') throw new Error('Invalid push status')
  return value as PushStatus
}

export async function changeSubscription(endpoint: string, add: boolean, csrfToken: string,
  request: typeof fetch = fetch): Promise<'ok' | 'invalid' | 'off' | 'denied'> {
  const response = await request('/api/v1/me/push-subscriptions', {
    method: add ? 'PUT' : 'DELETE', credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken, 'Content-Type': 'application/json' }, body: JSON.stringify({ endpoint }),
  })
  if (response.ok) return 'ok'
  if (response.status === 409) return 'off'
  if (response.status === 400) return 'invalid'
  if (response.status === 401 || response.status === 403) return 'denied'
  throw new Error('Request failed')
}

/** The VAPID public key (base64url) as the bytes the browser's push manager expects. */
export function keyBytes(base64url: string): Uint8Array {
  const base64 = base64url.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (base64url.length % 4)) % 4)
  return Uint8Array.from(atob(base64), char => char.charCodeAt(0))
}

export const pushSupported = () => typeof window !== 'undefined' && window.isSecureContext &&
  'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window

/** Registers the service worker once; harmless where it is not supported. */
export function registerServiceWorker() {
  if (typeof window === 'undefined' || !window.isSecureContext || !('serviceWorker' in navigator)) return
  navigator.serviceWorker.register('/sw.js').catch(() => { /* push stays unavailable */ })
}

export async function currentSubscription(): Promise<PushSubscription | null> {
  if (!pushSupported()) return null
  const registration = await navigator.serviceWorker.ready
  return registration.pushManager.getSubscription()
}

/** Asks for permission, subscribes this browser and tells the server; 'blocked' when the person said no. */
export async function enablePush(publicKey: string, csrfToken: string): Promise<'ok' | 'blocked' | 'invalid' | 'off' | 'denied'> {
  if (await Notification.requestPermission() !== 'granted') return 'blocked'
  const registration = await navigator.serviceWorker.ready
  const subscription = await registration.pushManager.getSubscription() ??
    await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(publicKey).buffer as ArrayBuffer })
  return changeSubscription(subscription.endpoint, true, csrfToken)
}

export async function disablePush(csrfToken: string): Promise<void> {
  const subscription = await currentSubscription()
  if (!subscription) return
  await changeSubscription(subscription.endpoint, false, csrfToken).catch(() => 'ok')
  await subscription.unsubscribe()
}

/**
 * On sign-out: this browser stops waking for the member who leaves, and the service worker's "last seen" marker is
 * cleared for whoever signs in next (security review 2026-11-26). Never blocks sign-out for more than two seconds.
 */
export async function forgetPushOnSignOut(csrfToken: string): Promise<void> {
  const done = (async () => {
    await disablePush(csrfToken)
    if (typeof caches !== 'undefined') await caches.delete('openclub-push')
  })().catch(() => { /* sign out anyway */ })
  await Promise.race([done, new Promise(resolve => setTimeout(resolve, 2000))])
}
