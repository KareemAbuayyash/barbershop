self.addEventListener('install', (event) => {
  event.waitUntil(self.skipWaiting())
})

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim())
})

self.addEventListener('push', (event) => {
  const message = event.data ? event.data.json() : {}
  event.waitUntil(Promise.all([
    self.registration.showNotification(message.title || 'BARBER HOUSE', {
      body: message.body || 'لديك إشعار جديد من المحل.',
      icon: '/favicon.svg',
      badge: '/favicon.svg',
      data: {
        id: message.id,
        path: message.path || '/notifications',
        guestBookingId: message.guestBookingId || null,
      },
      dir: 'rtl',
      lang: 'ar',
    }),
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
      clients.forEach((client) => client.postMessage({
        type: 'barbershop:notification-received',
        id: message.id,
      }))
    }),
  ]))
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const data = event.notification.data || {}
  const path = data.path || '/owner/notifications'
  const destinationUrl = new URL(path, self.location.origin)
  if (data.guestBookingId && destinationUrl.pathname.startsWith('/guest-notifications')) {
    destinationUrl.searchParams.set('bookingId', data.guestBookingId)
    if (data.id) destinationUrl.searchParams.set('notificationId', data.id)
  }
  const destination = destinationUrl.href
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
    for (const client of clients) {
      if (client.url.startsWith(self.location.origin) && 'focus' in client) {
        return client.navigate(destination).then(() => client.focus())
      }
    }
    return self.clients.openWindow(destination)
  }))
})
