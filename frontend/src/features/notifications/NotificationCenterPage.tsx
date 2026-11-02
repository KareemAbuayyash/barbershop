import { useCallback, useEffect, useState } from 'react'
import { Bell, BellRing, CheckCheck, RefreshCw } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { apiRequest, getErrorMessage } from '../../lib/api'
import { formatShopDateTime } from '../../lib/shop-time'

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

interface NotificationPreferences {
  preferences: Record<string, boolean>
}

interface PushConfiguration {
  enabled: boolean
  publicKey: string
}

const preferenceLabels: Record<string, string> = {
  BOOKING: 'إنشاء الحجوزات',
  BOOKING_UPDATE: 'تأكيد وتعديل وإلغاء الحجوزات',
  BOOKING_REMINDER: 'تذكيرات المواعيد والطابور',
  QUEUE: 'تحديثات قائمة الانتظار',
  INVENTORY: 'المخزون',
  SALES: 'المبيعات',
  PAYMENT: 'المدفوعات والفواتير',
  FINANCE: 'المصروفات',
  SERVICE: 'الخدمات والأسعار',
  SETTINGS: 'إعدادات المحل',
  DAILY_SUMMARY: 'الملخص اليومي للمالك',
}

function applicationKey(value: string) {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/')
    .padEnd(Math.ceil(value.length / 4) * 4, '=')
  return Uint8Array.from(window.atob(normalized), (character) => character.charCodeAt(0))
}

async function savePushSubscription(subscription: PushSubscription) {
  const keys = subscription.toJSON().keys
  if (!keys?.p256dh || !keys.auth) throw new Error('بيانات الاشتراك في الإشعارات غير مكتملة.')
  await apiRequest<void>('/api/notifications/subscriptions', {
    method: 'POST',
    body: JSON.stringify({
      endpoint: subscription.endpoint,
      keys: { p256dh: keys.p256dh, auth: keys.auth },
    }),
  })
}

function NotificationCenterPage() {
  const navigate = useNavigate()
  const isOwner = window.location.pathname.startsWith('/owner')
  const [center, setCenter] = useState<NotificationCenter | null>(null)
  const [preferences, setPreferences] = useState<Record<string, boolean>>({})
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [savingPreference, setSavingPreference] = useState('')
  const [pushConfigured, setPushConfigured] = useState(false)
  const [pushPublicKey, setPushPublicKey] = useState('')
  const [pushEnabled, setPushEnabled] = useState(false)
  const [pushBusy, setPushBusy] = useState(false)
  const [notice, setNotice] = useState('')

  const load = useCallback(async () => {
    try {
      const [notificationCenter, savedPreferences] = await Promise.all([
        apiRequest<NotificationCenter>('/api/notifications?page=0&size=50'),
        apiRequest<NotificationPreferences>('/api/notifications/preferences'),
      ])
      setError('')
      setCenter(notificationCenter)
      setPreferences(savedPreferences.preferences)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحميل الإشعارات.'))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    let active = true
    void Promise.all([
      apiRequest<NotificationCenter>('/api/notifications?page=0&size=50'),
      apiRequest<NotificationPreferences>('/api/notifications/preferences'),
    ])
      .then(([notificationCenter, savedPreferences]) => {
        if (!active) return
        setError('')
        setCenter(notificationCenter)
        setPreferences(savedPreferences.preferences)
      })
      .catch((cause: unknown) => {
        if (active) setError(getErrorMessage(cause, 'تعذر تحميل الإشعارات.'))
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => { active = false }
  }, [])

  useEffect(() => {
    let active = true
    async function initializePush() {
      if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) {
        return
      }
      if (!window.isSecureContext) return
      try {
        const [configuration, registration] = await Promise.all([
          apiRequest<PushConfiguration>('/api/notifications/configuration'),
          navigator.serviceWorker.ready,
        ])
        if (!active) return
        setPushConfigured(configuration.enabled)
        setPushPublicKey(configuration.publicKey)
        const subscription = await registration.pushManager.getSubscription()
        if (!subscription) return
        const status = await apiRequest<{ enabled: boolean }>(
          `/api/notifications/subscriptions/status?endpoint=${encodeURIComponent(subscription.endpoint)}`,
        )
        if (active) setPushEnabled(status.enabled)
      } catch (cause) {
        if (active) setError(getErrorMessage(cause, 'تعذر تهيئة إشعارات الهاتف.'))
      }
    }
    void initializePush()
    return () => { active = false }
  }, [])

  const togglePush = async () => {
    setPushBusy(true)
    setError('')
    setNotice('')
    try {
      const registration = await navigator.serviceWorker.ready
      let subscription = await registration.pushManager.getSubscription()
      if (!pushEnabled) {
        if (!pushConfigured || !pushPublicKey) {
          throw new Error('إشعارات الهاتف غير مهيأة على الخادم. أضف مفاتيح VAPID إلى إعدادات التشغيل.')
        }
        const permission = Notification.permission === 'granted'
          ? 'granted'
          : await Notification.requestPermission()
        if (permission !== 'granted') throw new Error('لم يتم السماح بإشعارات BARBER HOUSE على هذا الجهاز.')
        if (!subscription) {
          subscription = await registration.pushManager.subscribe({
            userVisibleOnly: true,
            applicationServerKey: applicationKey(pushPublicKey),
          })
        }
        await savePushSubscription(subscription)
        setPushEnabled(true)
        setNotice('تم تفعيل الإشعارات لهذا الجهاز.')
      } else {
        if (!subscription) throw new Error('اشتراك هذا الجهاز غير موجود. أعد تفعيل الإشعارات.')
        await apiRequest<void>('/api/notifications/subscriptions', {
          method: 'PATCH',
          body: JSON.stringify({ endpoint: subscription.endpoint, enabled: false }),
        })
        setPushEnabled(false)
        setNotice('تم إيقاف إشعارات هذا الجهاز.')
      }
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحديث إعدادات إشعارات الهاتف.'))
    } finally {
      setPushBusy(false)
    }
  }

  const updatePreference = async (eventType: string, enabled: boolean) => {
    const updatedPreferences = { ...preferences, [eventType]: enabled }
    setSavingPreference(eventType)
    setError('')
    try {
      const result = await apiRequest<NotificationPreferences>('/api/notifications/preferences', {
        method: 'PUT',
        body: JSON.stringify({ preferences: updatedPreferences }),
      })
      setPreferences(result.preferences)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر حفظ تفضيلات الإشعارات.'))
    } finally {
      setSavingPreference('')
    }
  }

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
          <h1>مركز الإشعارات</h1>
          <p>تابع التحديثات المرتبطة بحسابك، واحفظ تفضيلات إشعارات هذا الجهاز.</p>
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
        </div>
      </header>

      {error && <p className="notification-feedback notification-feedback--error" role="alert">{error}</p>}
      {notice && <p className="notification-feedback" role="status">{notice}</p>}

      <section className="notification-settings">
        <div className="notification-settings-title">
          <BellRing size={19} />
          <div><h2>إشعارات الهاتف</h2><p>تصل إلى هذا الجهاز حتى عند إغلاق الموقع، إذا كان المتصفح يدعم ذلك.</p></div>
        </div>
        <button type="button" onClick={() => void togglePush()} disabled={pushBusy || (!pushEnabled && !pushConfigured)}>
          {pushBusy ? 'جارٍ التحديث...' : pushEnabled ? 'إيقاف هذا الجهاز' : 'تفعيل على هذا الجهاز'}
        </button>
      </section>
      {!pushConfigured && <p className="notification-hint">يلزم إعداد مفاتيح VAPID على الخادم. يعمل Push عبر HTTPS أو localhost.</p>}

      <section className="notification-settings">
        <div className="notification-settings-title">
          <Bell size={19} />
          <div><h2>أنواع الإشعارات</h2><p>يمكنك إيقاف Push لأنواع محددة؛ ستبقى الإشعارات داخل المركز.</p></div>
        </div>
        <div className="notification-preferences">
          {Object.entries(preferenceLabels)
            .filter(([eventType]) => isOwner || !['INVENTORY', 'SALES', 'FINANCE', 'SERVICE', 'SETTINGS', 'DAILY_SUMMARY'].includes(eventType))
            .map(([eventType, label]) => (
              <label key={eventType}>
                <input
                  type="checkbox"
                  checked={preferences[eventType] ?? false}
                  disabled={savingPreference === eventType}
                  onChange={(event) => void updatePreference(eventType, event.target.checked)}
                />
                <span>{label}</span>
              </label>
            ))}
        </div>
      </section>

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
    </section>
  )
}

export default NotificationCenterPage

