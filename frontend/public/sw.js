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
