self.addEventListener('install', (event) => {
  event.waitUntil(self.skipWaiting())
})

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim())
})

self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return

  const requestUrl = new URL(event.request.url)
  if (requestUrl.origin !== self.location.origin || requestUrl.pathname.startsWith('/api/')) return

  event.respondWith((async () => {
    try {
      const response = await fetch(event.request)

      if (response.ok && event.request.mode === 'navigate') {
        const cache = await caches.open('barber-house-pages-v1')
        await cache.put('/', response.clone())
      }

      return response
    } catch (error) {
      const cachedResponse = await caches.match(event.request)
        || (event.request.mode === 'navigate' ? await caches.match('/') : undefined)

      if (cachedResponse) return cachedResponse
      throw error
    }
  })())
})

self.addEventListener('push', (event) => {
  const message = event.data ? event.data.json() : {}
  event.waitUntil(self.registration.showNotification(message.title || 'BARBER HOUSE', {
    body: message.body || 'لديك إشعار جديد من المحل.',
    icon: '/favicon.svg',
    badge: '/favicon.svg',
    data: { path: message.path || '/owner/notifications' },
    dir: 'rtl',
    lang: 'ar',
  }))
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const path = event.notification.data?.path || '/owner/notifications'
  const destination = new URL(path, self.location.origin).href
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
    for (const client of clients) {
      if (client.url.startsWith(self.location.origin) && 'focus' in client) {
        return client.navigate(destination).then(() => client.focus())
      }
    }
    return self.clients.openWindow(destination)
  }))
})
