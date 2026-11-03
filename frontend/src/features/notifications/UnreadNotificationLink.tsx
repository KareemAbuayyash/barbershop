import { useEffect, useState } from 'react'
import { Bell } from 'lucide-react'
import { Link } from 'react-router-dom'
import { apiRequest } from '../../lib/api'
import { useI18n } from '../../lib/i18n'

interface NotificationCount {
  unreadCount: number
}

function UnreadNotificationLink({ to, label, onClick }: { to: string; label: string; onClick?: () => void }) {
  const { t, language } = useI18n()
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
    const refreshWhenVisible = () => {
      if (document.visibilityState === 'visible') void loadCount()
    }
    const interval = window.setInterval(refreshWhenVisible, 15_000)
    window.addEventListener('barbershop:notifications-updated', loadCount)
    window.addEventListener('focus', loadCount)
    document.addEventListener('visibilitychange', refreshWhenVisible)
    return () => {
      active = false
      window.clearInterval(interval)
      window.removeEventListener('barbershop:notifications-updated', loadCount)
      window.removeEventListener('focus', loadCount)
      document.removeEventListener('visibilitychange', refreshWhenVisible)
    }
  }, [])

  return (
    <Link className="notification-header-link" to={to} onClick={() => {
      onClick?.()
      window.dispatchEvent(new Event('barbershop:close-site-menu'))
      window.dispatchEvent(new Event('barbershop:close-admin-menu'))
    }}
      aria-label={`${label}${unreadCount ? `, ${unreadCount.toLocaleString(language)} ${t('غير مقروء')}` : ''}${loadFailed ? `, ${t('تعذر تحديث العداد')}` : ''}`}
      title={loadFailed ? t('تعذر تحديث عداد الإشعارات') : undefined}>
      <Bell size={19} aria-hidden="true" />
      {unreadCount > 0 && <span>{unreadCount.toLocaleString(language)}</span>}
    </Link>
  )
}

export default UnreadNotificationLink
