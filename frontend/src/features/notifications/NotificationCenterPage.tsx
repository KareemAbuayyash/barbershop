import { useCallback, useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { Bell, CheckCheck, RefreshCw, Settings, X } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { apiRequest, getErrorMessage } from '../../lib/api'
import { formatShopDateTime } from '../../lib/shop-time'
import NotificationSettingsPage from './NotificationSettingsPage'

interface NotificationItem {
  id: string
  eventType: string
  title: string
  body: string
  targetPath: string
  createdAt: string
  readAt: string | null
}

interface NotificationCenter {
  content: NotificationItem[]
  unreadCount: number
  page: number
  size: number
  totalElements: number
  totalPages: number
}

function NotificationCenterPage() {
  const navigate = useNavigate()
  const isOwner = window.location.pathname.startsWith('/owner')
  const [center, setCenter] = useState<NotificationCenter | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [settingsOpen, setSettingsOpen] = useState(false)

  const load = useCallback(async () => {
    try {
      const result = await apiRequest<NotificationCenter>('/api/notifications?page=0&size=50')
      setCenter(result)
      setError('')
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحميل الإشعارات.'))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    if (!settingsOpen) return
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setSettingsOpen(false)
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [settingsOpen])

  useEffect(() => {
    const refresh = () => { if (document.visibilityState === 'visible') void load() }
    const onServiceWorkerMessage = (event: MessageEvent) => {
      if (event.data?.type === 'barbershop:notification-received') void load()
    }
    const interval = window.setInterval(refresh, 15_000)
    window.addEventListener('focus', refresh)
    navigator.serviceWorker?.addEventListener('message', onServiceWorkerMessage)
    return () => {
      window.clearInterval(interval)
      window.removeEventListener('focus', refresh)
      navigator.serviceWorker?.removeEventListener('message', onServiceWorkerMessage)
    }
  }, [load])

  const markRead = async (notification: NotificationItem) => {
    if (!notification.readAt) {
      try {
        await apiRequest<void>(`/api/notifications/${notification.id}/read`, { method: 'PATCH' })
        window.dispatchEvent(new Event('barbershop:notifications-updated'))
      } catch (cause) {
        setError(getErrorMessage(cause, 'تعذر تحديث حالة الإشعار.'))
        return
      }
    }
    navigate(notification.targetPath)
  }

  const markAllRead = async () => {
    try {
      await apiRequest<void>('/api/notifications/read-all', { method: 'POST' })
      await load()
      window.dispatchEvent(new Event('barbershop:notifications-updated'))
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحديد الإشعارات كمقروءة.'))
    }
  }

  return (
    <section className={`notification-center${isOwner ? ' notification-center--owner' : ''}`}>
      <header className="notification-center-heading">
        <div>
          <span className="notification-center-eyebrow">{isOwner ? 'إدارة المحل' : 'مساحة العميل'}</span>
          <h1>الإشعارات</h1>
          <p>كل تحديثات حسابك في مكان واحد.</p>
        </div>
        <div className="notification-center-actions">
          <span className="notification-unread-count">{(center?.unreadCount ?? 0).toLocaleString('ar')} غير مقروء</span>
          <button type="button" onClick={() => { setLoading(true); void load() }} disabled={loading}>
            <RefreshCw size={16} /> تحديث
          </button>
          {!!center?.unreadCount && (
            <button type="button" onClick={() => void markAllRead()}>
              <CheckCheck size={16} /> تحديد الكل كمقروء
            </button>
          )}
          <button className="notification-settings-trigger" type="button"
            aria-label="إعدادات الإشعارات" title="إعدادات الإشعارات"
            onClick={() => setSettingsOpen(true)}>
            <Settings size={17} /> <span>الإعدادات</span>
          </button>
        </div>
      </header>

      {error && <p className="notification-feedback notification-feedback--error" role="alert">{error}</p>}

      <section className="notification-feed" aria-label="قائمة الإشعارات">
        <h2>آخر الإشعارات</h2>
        {loading ? <p className="notification-empty" role="status">جارٍ تحميل الإشعارات...</p>
          : center?.content.length ? center.content.map((item) => (
            <button
              type="button"
              className={`notification-feed-item${item.readAt ? '' : ' notification-feed-item--unread'}`}
              key={item.id}
              onClick={() => void markRead(item)}
            >
              <span className="notification-feed-icon"><Bell size={18} /></span>
              <span className="notification-feed-copy">
                <strong>{item.title}</strong>
                <span>{item.body}</span>
                <small>{formatShopDateTime(item.createdAt)}</small>
              </span>
              {!item.readAt && <span className="notification-unread-dot" aria-label="غير مقروء" />}
            </button>
          )) : <p className="notification-empty">لا توجد إشعارات حالياً.</p>}
      </section>
      {settingsOpen && createPortal(
        <div className="notification-settings-modal-backdrop" role="presentation"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setSettingsOpen(false)
          }}>
          <section className="notification-settings-modal" role="dialog" aria-modal="true"
            aria-label="إعدادات الإشعارات">
            <button className="notification-settings-modal-close" type="button" aria-label="إغلاق الإعدادات"
              onClick={() => setSettingsOpen(false)}><X size={19} /></button>
            <NotificationSettingsPage embedded />
          </section>
        </div>,
        document.body,
      )}
    </section>
  )
}

export default NotificationCenterPage
