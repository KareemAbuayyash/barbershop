import { useEffect, useState } from 'react'
import { Bell, BellRing } from 'lucide-react'
import { Link } from 'react-router-dom'
import { apiRequest, getErrorMessage } from '../../lib/api'

interface NotificationPreferences {
  preferences: Record<string, boolean>
}

interface PushConfiguration {
  enabled: boolean
  publicKey: string
}

const preferenceLabels: Record<string, string> = {
  BOOKING_CREATED: 'الحجوزات الجديدة',
  BOOKING_CONFIRMED: 'تأكيد الحجوزات',
  BOOKING_REJECTED: 'رفض الحجوزات',
  BOOKING_CANCELLED: 'إلغاء الحجوزات',
  BOOKING_RESCHEDULED: 'تعديل مواعيد الحجوزات',
  BOOKING_PROPOSAL: 'المواعيد المقترحة',
  BOOKING_PROPOSAL_RESPONSE: 'الرد على المواعيد المقترحة',
  BOOKING_REMINDER: 'تذكيرات المواعيد وقائمة الانتظار',
  QUEUE: 'تحديثات قائمة الانتظار',
  INVENTORY: 'المخزون',
  INVENTORY_LOW: 'انخفاض المخزون',
  INVENTORY_OUT: 'نفاد المخزون',
  SALES: 'المبيعات',
  PAYMENT: 'المدفوعات والفواتير',
  PAYMENT_SETTLED: 'تسديد المدفوعات',
  PAYMENT_DUE: 'المبالغ المستحقة',
  FINANCE: 'المصروفات',
  SERVICE: 'الخدمات والأسعار',
  SETTINGS: 'إعدادات المحل',
  DAILY_SUMMARY: 'الملخص اليومي',
}

function applicationKey(value: string) {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/')
    .padEnd(Math.ceil(value.length / 4) * 4, '=')
  return Uint8Array.from(window.atob(normalized), (character) => character.charCodeAt(0))
}

async function savePushSubscription(subscription: PushSubscription) {
  const keys = subscription.toJSON().keys
  if (!keys?.p256dh || !keys.auth) throw new Error('بيانات الاشتراك غير مكتملة.')
  await apiRequest<void>('/api/notifications/subscriptions', {
    method: 'POST',
    body: JSON.stringify({ endpoint: subscription.endpoint, keys: { p256dh: keys.p256dh, auth: keys.auth } }),
  })
}

function subscriptionUsesApplicationKey(subscription: PushSubscription, publicKey: string) {
  const currentKey = subscription.options.applicationServerKey
  if (!currentKey) return false

  const current = new Uint8Array(currentKey)
  const expected = applicationKey(publicKey)
  return current.length === expected.length && current.every((byte, index) => byte === expected[index])
}

function NotificationSettingsPage({ embedded = false }: { embedded?: boolean }) {
  const isOwner = window.location.pathname.startsWith('/owner')
  const [preferences, setPreferences] = useState<Record<string, boolean>>({})
  const [pushConfigured, setPushConfigured] = useState(false)
  const [pushPublicKey, setPushPublicKey] = useState('')
  const [pushEnabled, setPushEnabled] = useState(false)
  const [busy, setBusy] = useState(false)
  const [saving, setSaving] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  useEffect(() => {
    let active = true
    async function loadSettings() {
      try {
        const result = await apiRequest<NotificationPreferences>('/api/notifications/preferences')
        if (active) setPreferences(result.preferences)
      } catch (cause) {
        if (active) setError(getErrorMessage(cause, 'تعذر تحميل إعدادات الإشعارات.'))
      }
      if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)
        || !window.isSecureContext) return
      try {
        const [configuration, registration] = await Promise.all([
          apiRequest<PushConfiguration>('/api/notifications/configuration'),
          navigator.serviceWorker.ready,
        ])
        if (!active) return
        setPushConfigured(configuration.enabled)
        setPushPublicKey(configuration.publicKey)
        let subscription = await registration.pushManager.getSubscription()
        if (subscription && configuration.enabled
          && !subscriptionUsesApplicationKey(subscription, configuration.publicKey)) {
          const unsubscribed = await subscription.unsubscribe()
          if (!unsubscribed) throw new Error('تعذر تجديد اشتراك الإشعارات القديم على هذا الجهاز.')
          subscription = null
        }
        // On another browser/device, reuse an already granted permission without
        // making the user repeat the setup click. Never reassign an existing
        // subscription here; it may belong to a guest booking on this device.
        if (!subscription && configuration.enabled && Notification.permission === 'granted') {
          subscription = await registration.pushManager.subscribe({
            userVisibleOnly: true,
            applicationServerKey: applicationKey(configuration.publicKey),
          })
          await savePushSubscription(subscription)
          if (active) {
            setPushEnabled(true)
            setNotice('تم تفعيل الإشعارات لهذا الجهاز الذي سبق أن سمح بها.')
          }
          return
        }
        if (!subscription) return
        const result = await apiRequest<{ enabled: boolean }>(
          `/api/notifications/subscriptions/status?endpoint=${encodeURIComponent(subscription.endpoint)}`,
        )
        if (active) setPushEnabled(result.enabled)
      } catch (cause) {
        if (active) setError(getErrorMessage(cause, 'تعذر تجهيز إشعارات هذا الجهاز.'))
      }
    }
    void loadSettings()
    return () => { active = false }
  }, [])

  const togglePush = async () => {
    setBusy(true)
    setError('')
    setNotice('')
    try {
      const registration = await navigator.serviceWorker.ready
      let subscription = await registration.pushManager.getSubscription()
      if (!pushEnabled) {
        if (!pushConfigured || !pushPublicKey) throw new Error('إشعارات الهاتف غير مهيأة على الخادم.')
        if (subscription && !subscriptionUsesApplicationKey(subscription, pushPublicKey)) {
          const unsubscribed = await subscription.unsubscribe()
          if (!unsubscribed) throw new Error('تعذر تجديد اشتراك الإشعارات القديم على هذا الجهاز.')
          subscription = null
        }
        const permission = Notification.permission === 'granted'
          ? 'granted'
          : await Notification.requestPermission()
        if (permission !== 'granted') throw new Error('لم تسمح بإشعارات BARBER HOUSE على هذا الجهاز.')
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
        setNotice('تم إيقاف الإشعارات لهذا الجهاز.')
      }
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحديث إعدادات هذا الجهاز.'))
    } finally {
      setBusy(false)
    }
  }

  const updatePreference = async (eventType: string, enabled: boolean) => {
    const updated = { ...preferences, [eventType]: enabled }
    setSaving(eventType)
    setError('')
    try {
      const result = await apiRequest<NotificationPreferences>('/api/notifications/preferences', {
        method: 'PUT',
        body: JSON.stringify({ preferences: updated }),
      })
      setPreferences(result.preferences)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر حفظ تفضيلات الإشعارات.'))
    } finally {
      setSaving('')
    }
  }

  return (
    <section className={`notification-settings-page${embedded ? ' notification-settings-page--embedded' : ''}`} dir="rtl">
      {!embedded && <header className="notification-settings-heading">
        <div>
          <span>{isOwner ? 'إدارة المحل' : 'إعدادات الحساب'}</span>
          <h1>إعدادات الإشعارات</h1>
          <p>اسمح بالإشعارات مرة واحدة لهذا الجهاز؛ سيرتبط تلقائياً بأي حساب تسجل الدخول به.</p>
        </div>
        <Link to={isOwner ? '/owner/settings' : '/my-settings'}>العودة للإعدادات</Link>
      </header>}
      {error && <p className="notification-feedback notification-feedback--error" role="alert">{error}</p>}
      {notice && <p className="notification-feedback" role="status">{notice}</p>}
      <section className="notification-settings-card">
        <div className="notification-settings-title">
          <BellRing size={20} />
          <div><h2>إشعارات هذا الجهاز</h2><p>السماح من المتصفح مرة واحدة لكل هاتف أو تثبيت. الحساب الحالي يرتبط تلقائياً عند تسجيل الدخول، وينفصل عند تسجيل الخروج.</p></div>
        </div>
        <button type="button" onClick={() => void togglePush()} disabled={busy || (!pushEnabled && !pushConfigured)}>
          {busy ? 'جارٍ التحديث...' : pushEnabled ? 'إيقاف هذا الجهاز' : 'تفعيل على هذا الجهاز'}
        </button>
        {!pushConfigured && <p className="notification-settings-hint">يلزم إعداد مفاتيح VAPID على الخادم واستخدام HTTPS.</p>}
      </section>
      <section className="notification-settings-card">
        <div className="notification-settings-title">
          <Bell size={20} />
          <div><h2>أنواع الإشعارات</h2><p>إيقاف Push لنوع معين لا يخفيه من مركز الإشعارات.</p></div>
        </div>
        <div className="notification-preferences">
          {Object.entries(preferenceLabels)
            .filter(([type]) => isOwner || ![
              'INVENTORY', 'INVENTORY_LOW', 'INVENTORY_OUT', 'SALES', 'PAYMENT',
              'FINANCE', 'SERVICE', 'SETTINGS', 'DAILY_SUMMARY',
            ].includes(type))
            .map(([type, label]) => (
              <label key={type}>
                <span>{label}</span>
                <input type="checkbox" checked={preferences[type] ?? false}
                  disabled={saving === type} onChange={(event) => void updatePreference(type, event.target.checked)} />
              </label>
            ))}
        </div>
      </section>
    </section>
  )
}

export default NotificationSettingsPage
