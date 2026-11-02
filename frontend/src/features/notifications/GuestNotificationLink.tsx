import { useEffect, useState } from 'react'
import { Bell } from 'lucide-react'
import { Link } from 'react-router-dom'
import { apiRequest } from '../../lib/api'
import { readGuestBookingLinks } from './guest-booking-links'

interface GuestNotificationCount {
  unreadCount: number
}

function GuestNotificationLink({ onClick }: { onClick?: () => void }) {
  const [latestToken, setLatestToken] = useState('')
  const [unreadCount, setUnreadCount] = useState(0)

  useEffect(() => {
    let active = true
    const refresh = async () => {
      const links = readGuestBookingLinks()
      const latest = links[links.length - 1]?.token ?? ''
      setLatestToken(latest)
      if (!latest) {
        setUnreadCount(0)
        return
      }
      try {
        const center = await apiRequest<GuestNotificationCount>(
          `/api/guest-notifications/${encodeURIComponent(latest)}?page=0&size=1`,
        )
        if (active) setUnreadCount(center.unreadCount)
      } catch {
        if (active) setUnreadCount(0)
      }
    }
    void refresh()
    const interval = window.setInterval(() => {
      if (document.visibilityState === 'visible') void refresh()
    }, 15_000)
    window.addEventListener('barbershop:guest-bookings-updated', refresh)
    window.addEventListener('focus', refresh)
    const onServiceWorkerMessage = (event: MessageEvent) => {
      if (event.data?.type === 'barbershop:notification-received') void refresh()
    }
    navigator.serviceWorker?.addEventListener('message', onServiceWorkerMessage)
    return () => {
      active = false
      window.clearInterval(interval)
      window.removeEventListener('barbershop:guest-bookings-updated', refresh)
      window.removeEventListener('focus', refresh)
      navigator.serviceWorker?.removeEventListener('message', onServiceWorkerMessage)
    }
  }, [])

  if (!latestToken) return null
  return (
    <Link className="notification-header-link" to={`/guest-notifications/${encodeURIComponent(latestToken)}`}
      onClick={() => {
        onClick?.()
        window.dispatchEvent(new Event('barbershop:close-site-menu'))
        window.dispatchEvent(new Event('barbershop:close-admin-menu'))
      }}
      aria-label={`إشعارات الحجوزات${unreadCount ? `، ${unreadCount} غير مقروء` : ''}`}
      title="إشعارات الحجوزات">
      <Bell size={19} aria-hidden="true" />
      {unreadCount > 0 && <span>{unreadCount.toLocaleString('ar')}</span>}
    </Link>
  )
}

export default GuestNotificationLink
