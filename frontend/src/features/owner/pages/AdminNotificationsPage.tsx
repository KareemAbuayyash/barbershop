import { useEffect, useState } from 'react'
import { AlertTriangle, Bell, BellRing, CalendarDays, Package, RefreshCw } from 'lucide-react'
import { Link } from 'react-router-dom'
import { apiRequest, getErrorMessage } from '../../../lib/api'
import type { AdminNotification } from '../admin-api'

async function savePushSubscription(subscription: PushSubscription) {
  const keys = subscription.toJSON().keys
  if (!keys?.p256dh || !keys.auth) throw new Error('بيانات الاشتراك في الإشعارات غير مكتملة.')
  await apiRequest<void>('/api/admin/push-subscriptions', {
    method: 'POST',
    body: JSON.stringify({
      endpoint: subscription.endpoint,
      keys: { p256dh: keys.p256dh, auth: keys.auth },
    }),
  })
}

function AdminNotificationsPage() {
  const [notifications, setNotifications] = useState<AdminNotification[]>([])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [pushConfigured, setPushConfigured] = useState(false)
  const [pushPublicKey, setPushPublicKey] = useState('')
  const [pushSubscribed, setPushSubscribed] = useState(false)
  const [pushBusy, setPushBusy] = useState(false)
  const [pushError, setPushError] = useState('')
  const [pushNotice, setPushNotice] = useState('')

  const load = async () => {
    try {
      setNotifications(await apiRequest<AdminNotification[]>('/api/admin/notifications'))
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحميل الإشعارات.'))
    } finally { setLoading(false) }
  }
  useEffect(() => {
    let active = true
    void apiRequest<AdminNotification[]>('/api/admin/notifications')
      .then((data) => { if (active) setNotifications(data) })
      .catch((cause: unknown) => {
        if (active) setError(getErrorMessage(cause, 'تعذر تحميل الإشعارات.'))
      })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  useEffect(() => {
    let active = true
    const initializePush = async () => {
      if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) {
        setPushError('هذا المتصفح لا يدعم إشعارات PWA.')
        return
      }
      if (!window.isSecureContext) {
        setPushError('إشعارات الهاتف تحتاج HTTPS أو تشغيل التطبيق على localhost.')
        return
      }
      try {
        const [configuration, registration] = await Promise.all([
          apiRequest<{ enabled: boolean; publicKey: string }>('/api/push/configuration'),
          navigator.serviceWorker.register('/sw.js'),
        ])
        if (!active) return
        setPushConfigured(configuration.enabled)
        setPushPublicKey(configuration.publicKey)
        if (!configuration.enabled) return
        const subscription = await registration.pushManager.getSubscription()
        if (!active) return
        setPushSubscribed(Boolean(subscription))
        if (subscription) await savePushSubscription(subscription)
      } catch (cause) {
        if (active) setPushError(getErrorMessage(cause, 'تعذر تهيئة إشعارات الهاتف.'))
      }
    }
    void initializePush()
    return () => { active = false }
  }, [])

  const togglePushNotifications = async () => {
    setPushBusy(true)
    setPushError('')
    setPushNotice('')
    try {
      if (pushSubscribed) {
        const registration = await navigator.serviceWorker.ready
        const current = await registration.pushManager.getSubscription()
        if (current) {
          await apiRequest<void>(`/api/admin/push-subscriptions?endpoint=${encodeURIComponent(current.endpoint)}`, {
            method: 'DELETE',
          })
          await current.unsubscribe()
        }
        setPushSubscribed(false)
        setPushNotice('تم إيقاف إشعارات هذا الجهاز.')
        return
      }

      if (!pushConfigured || !pushPublicKey) {
        throw new Error('إشعارات الهاتف غير مهيأة على الخادم. أضف مفاتيح VAPID إلى إعدادات التشغيل.')
      }
      const permission = await (Notification.permission === 'granted'
        ? Promise.resolve('granted')
        : Notification.requestPermission())
      if (permission !== 'granted') throw new Error('لم يتم السماح بإشعارات BARBER HOUSE على هذا الجهاز.')

      const applicationServerKey = Uint8Array.from(
        window.atob(pushPublicKey.replace(/-/g, '+').replace(/_/g, '/')
          .padEnd(Math.ceil(pushPublicKey.length / 4) * 4, '=')),
        (character) => character.charCodeAt(0),
      )
      const registration = await navigator.serviceWorker.ready
      const subscription = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey,
      })
      await savePushSubscription(subscription)
      setPushConfigured(true)
      setPushSubscribed(true)
      setPushNotice('تم تفعيل الإشعارات لهذا الجهاز.')
    } catch (cause) {
      setPushError(getErrorMessage(cause, 'تعذر تحديث إعدادات إشعارات الهاتف.'))
    } finally {
      setPushBusy(false)
    }
  }

  return (
    <div className="admin-page">
      <section className="admin-panel">
        <div className="admin-panel-heading">
          <div><span className="admin-eyebrow">تنبيهات تحتاج متابعة</span><h2>الإشعارات</h2></div>
          <button type="button" className="admin-secondary-button" onClick={() => {
            setLoading(true)
            setError('')
            void load()
          }} disabled={loading}>
            <RefreshCw size={16} /> تحديث
          </button>
        </div>
        {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
        <section className="admin-push-settings" aria-label="إشعارات الهاتف">
          <div>
            <strong><BellRing size={17} /> إشعارات الهاتف</strong>
            <p>استقبل تنبيهات الحجوزات الجديدة وانخفاض المخزون حتى عند إغلاق الصفحة.</p>
            {!pushConfigured && <small>يلزم إعداد مفاتيح VAPID على الخادم، وتثبيت التطبيق على iPhone لإشعارات iOS.</small>}
          </div>
          <button type="button" className="admin-secondary-button" onClick={() => void togglePushNotifications()}
            disabled={pushBusy || (!pushConfigured && !pushSubscribed)}>
            {pushBusy ? 'جارٍ التحديث...' : pushSubscribed ? 'إيقاف هذا الجهاز' : 'تفعيل على هذا الجهاز'}
          </button>
        </section>
        {pushError && <p className="admin-message admin-message--error" role="alert">{pushError}</p>}
        {pushNotice && <p className="admin-message" role="status">{pushNotice}</p>}
        {loading ? <p className="admin-state" role="status">جارٍ تحميل الإشعارات...</p>
          : notifications.length ? (
            <div className="admin-notification-list">{notifications.map((item) => {
              const isStock = item.type === 'LOW_STOCK'
              return (
                <Link className="admin-notification-card" to={isStock ? '/owner/inventory' : '/owner/bookings'}
                  key={item.type}>
                  <span className="admin-stat-icon">{isStock ? <Package size={19} /> : <CalendarDays size={19} />}</span>
                  <span><strong>{item.message}</strong><small>اضغط لفتح التفاصيل</small></span>
                  <span className="admin-notification-count">{item.count.toLocaleString('ar')}</span>
                </Link>
              )
            })}</div>
          ) : <div className="admin-empty"><Bell size={24} /><h3>لا توجد تنبيهات حالية</h3>
            <p><AlertTriangle size={15} /> ستظهر هنا الحجوزات المعلقة والمخزون المنخفض.</p></div>}
      </section>
    </div>
  )
}

export default AdminNotificationsPage
