import { useEffect, useState } from 'react'
import { Bell } from 'lucide-react'
import { Link } from 'react-router-dom'
import { apiRequest } from '../../lib/api'

interface NotificationCount {
  unreadCount: number
}

function UnreadNotificationLink({ to, label }: { to: string; label: string }) {
  const [unreadCount, setUnreadCount] = useState(0)
  const [loadFailed, setLoadFailed] = useState(false)

  useEffect(() => {
    let active = true
    const loadCount = async () => {
      try {
        const result = await apiRequest<NotificationCount>('/api/notifications?page=0&size=1')
        if (active) {
          setUnreadCount(result.unreadCount)
          setLoadFailed(false)
        }
      } catch {
        if (active) setLoadFailed(true)
      }
    }
    void loadCount()
    const interval = window.setInterval(() => void loadCount(), 60_000)
    window.addEventListener('barbershop:notifications-updated', loadCount)
    return () => {
      active = false
      window.clearInterval(interval)
      window.removeEventListener('barbershop:notifications-updated', loadCount)
    }
  }, [])

  return (
    <Link
      className="notification-header-link"
      to={to}
      aria-label={`${label}${unreadCount ? `، ${unreadCount} غير مقروء` : ''}${loadFailed ? '، تعذر تحديث العدّاد' : ''}`}
      title={loadFailed ? 'تعذر تحديث عدّاد الإشعارات' : undefined}
    >
      <Bell size={18} />
      {unreadCount > 0 && <span>{unreadCount > 99 ? '99+' : unreadCount.toLocaleString('ar')}</span>}
    </Link>
  )
}

export default UnreadNotificationLink

