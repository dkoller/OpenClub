/* OpenClub service worker (OpenAPI 1.33.0): shows push notifications. The push itself is empty; the text is read
   from the club's own server with the signed-in session, so nothing personal passes through the push service. */
const words = {
  de: { title: 'Neues vom Verein', body: 'Öffnen Sie die App, um es zu sehen.' },
  fr: { title: 'Du nouveau au club', body: 'Ouvrez l’application pour le voir.' },
  it: { title: 'Novità dal club', body: 'Aprite l’app per vederlo.' },
  en: { title: 'News from the club', body: 'Open the app to see it.' },
}
const text = words[(self.navigator.language || 'de').slice(0, 2)] || words.de
const STORE = 'openclub-push'

async function lastSeen() {
  try {
    const response = await (await caches.open(STORE)).match('/push-last-seen')
    return response ? await response.text() : ''
  } catch { return '' }
}

async function remember(value) {
  try { await (await caches.open(STORE)).put('/push-last-seen', new Response(value)) } catch { /* storage may be unavailable */ }
}

self.addEventListener('push', event => {
  event.waitUntil((async () => {
    const since = await lastSeen()
    let items = []
    try {
      const response = await fetch('/api/v1/me/push-inbox' + (since ? '?since=' + encodeURIComponent(since) : ''),
        { credentials: 'same-origin', cache: 'no-store' })
      if (response.ok) items = (await response.json()).items || []
    } catch { /* offline or signed out: show the generic notice */ }
    if (items.length === 0) {
      await self.registration.showNotification(text.title, { body: text.body, icon: '/icon-192.png', tag: 'openclub' })
      return
    }
    await remember(items[0].createdAt)
    for (const item of items.slice(0, 3).reverse()) {
      await self.registration.showNotification(String(item.subject).replace(/^\[[^\]]*\]\s*/, ''),
        { body: text.body, icon: '/icon-192.png', tag: 'openclub-' + item.createdAt })
    }
  })())
})

self.addEventListener('notificationclick', event => {
  event.notification.close()
  event.waitUntil((async () => {
    const open = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
    if (open[0]) return open[0].focus()
    return self.clients.openWindow('/#/')
  })())
})
