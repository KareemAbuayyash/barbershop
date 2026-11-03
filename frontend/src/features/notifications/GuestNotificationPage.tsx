import { useCallback, useEffect, useState } from 'react'
import { Bell, BellRing, CheckCheck, RefreshCw } from 'lucide-react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { apiRequest, getErrorMessage } from '../../lib/api'
import { formatShopDateTime } from '../../lib/shop-time'
import {
  findGuestBookingToken,
  latestGuestBookingToken,
  readGuestBookingLinks,
  rememberGuestBookingLink,
} from './guest-booking-links'

interface GuestNotificationItem {
  id: string
  eventType: string
  title: string
  body: string
  targetPath: string
  createdAt: string
  readAt: string | null
}

interface GuestNotificationCenter {
  content: GuestNotificationItem[]
  unreadCount: number
}

interface GuestPushConfiguration {
  enabled: boolean
  publicKey: string
  bookingId?: string
}

const TOKEN_STORAGE = 'barber-house-guest-notification-token'
const GUEST_PUSH_OPT_IN = 'barber-house-guest-push-opt-in'

function applicationKey(value: string) {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/')
    .padEnd(Math.ceil(value.length / 4) * 4, '=')
  return Uint8Array.from(window.atob(normalized), (character) => character.charCodeAt(0))
}

function GuestNotificationPage() {
  const { token: routeToken } = useParams<{ token?: string }>()
  const [searchParams] = useSearchParams()
  const bookingIdFromQuery = searchParams.get('bookingId') ?? ''
  const notificationIdFromQuery = searchParams.get('notificationId') ?? ''
  const navigate = useNavigate()
  const [token, setToken] = useState(routeToken ?? '')
  const [center, setCenter] = useState<GuestNotificationCenter | null>(null)
  const [configuration, setConfiguration] = useState<GuestPushConfiguration | null>(null)
  const [pushEnabled, setPushEnabled] = useState(false)
  const [pushSupported, setPushSupported] = useState(false)
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  useEffect(() => {
    let active = true
    if (routeToken) {
      setToken(routeToken)
      window.localStorage.setItem(TOKEN_STORAGE, routeToken)
      return () => { active = false }
    }
    const resolveToken = async () => {
      if (!bookingIdFromQuery) {
        const latest = latestGuestBookingToken()
        setToken(latest)
        if (!latest) setLoading(false)
        return
      }
      const linkedToken = findGuestBookingToken(bookingIdFromQuery)
      if (linkedToken) {
        setToken(linkedToken)
        return
      }

      // Older installs only stored the most recent token. Verify it before
      // using it for a notification that belongs to a specific booking.
      const legacyToken = latestGuestBookingToken()
      if (legacyToken) {
        try {
          const config = await apiRequest<GuestPushConfiguration>(
            `/api/guest-notifications/${legacyToken}/configuration`,
          )
          if (active && config.bookingId === bookingIdFromQuery) {
            rememberGuestBookingLink(bookingIdFromQuery, legacyToken)
            setToken(legacyToken)
            return
          }
        } catch {
          // Show the missing-link state below.
        }
      }
      if (active) {
        setToken('')
        setError('رابط متابعة هذا الحجز غير محفوظ على هذا الجهاز. افتح رابط الحجز الأصلي لمتابعته.')
        setLoading(false)
      }
    }
    void resolveToken()
    return () => { active = false }
  }, [bookingIdFromQuery, routeToken])

  const load = useCallback(async () => {
    if (!token) {
      setError('رابط إشعارات الحجز غير موجود أو انتهت صلاحيته.')
      setLoading(false)
      return
    }
    try {
      const result = await apiRequest<GuestNotificationCenter>(`/api/guest-notifications/${token}?page=0&size=50`)
      setCenter(result)
      setError('')
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحميل إشعارات هذا الحجز.'))
    } finally {
      setLoading(false)
    }
  }, [token])

  useEffect(() => {
    if (!token) return
    let active = true
    void Promise.all([
      apiRequest<GuestNotificationCenter>(`/api/guest-notifications/${token}?page=0&size=50`),
      apiRequest<GuestPushConfiguration>(`/api/guest-notifications/${token}/configuration`),
    ])
      .then(async ([savedCenter, savedConfiguration]) => {
        if (!active) return
        setCenter(savedCenter)
        setConfiguration(savedConfiguration)
        if (savedConfiguration.bookingId) rememberGuestBookingLink(savedConfiguration.bookingId, token)
        const supported = 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window && window.isSecureContext
        setPushSupported(supported)
        if (!supported) return
        const registration = await navigator.serviceWorker.ready
        const subscription = await registration.pushManager.getSubscription()
        if (!subscription) return
        const status = await apiRequest<{ enabled: boolean }>(
          `/api/guest-notifications/${token}/subscriptions/status?endpoint=${encodeURIComponent(subscription.endpoint)}`,
        )
        if (active) setPushEnabled(status.enabled)
      })
      .catch((cause: unknown) => {
        if (active) setError(getErrorMessage(cause, 'تعذر تحميل إشعارات هذا الحجز.'))
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => { active = false }
  }, [token])

  useEffect(() => {
    const refresh = () => void load()
    const onMessage = (event: MessageEvent) => {
      if (event.data?.type === 'barbershop:notification-received') refresh()
    }
    const interval = window.setInterval(refresh, 10_000)
    window.addEventListener('focus', refresh)
    navigator.serviceWorker?.addEventListener('message', onMessage)
    return () => {
      window.clearInterval(interval)
      window.removeEventListener('focus', refresh)
      navigator.serviceWorker?.removeEventListener('message', onMessage)
    }
  }, [load])

  const togglePush = async () => {
    if (!token || !configuration?.enabled) return
    setBusy(true)
    setError('')
    setNotice('')
    try {
      const registration = await navigator.serviceWorker.ready
      let subscription = await registration.pushManager.getSubscription()
      if (!pushEnabled) {
        const permission = Notification.permission === 'granted'
          ? 'granted'
          : await Notification.requestPermission()
        if (permission !== 'granted') throw new Error('لم يتم السماح بإشعارات هذا الحجز.')
        if (!subscription) {
          subscription = await registration.pushManager.subscribe({
            userVisibleOnly: true,
            applicationServerKey: applicationKey(configuration.publicKey),
          })
        }
        const keys = subscription.toJSON().keys
        if (!keys?.p256dh || !keys.auth) throw new Error('بيانات اشتراك الهاتف غير مكتملة.')
        await apiRequest<void>(`/api/guest-notifications/${token}/subscriptions`, {
          method: 'POST',
          body: JSON.stringify({ endpoint: subscription.endpoint, keys: { p256dh: keys.p256dh, auth: keys.auth } }),
        })
        window.localStorage.setItem(GUEST_PUSH_OPT_IN, 'true')
        setPushEnabled(true)
        setNotice('تم تفعيل إشعارات حالة الحجز على هذا الجهاز.')
      } else {
        if (!subscription) throw new Error('اشتراك هذا الجهاز غير موجود.')
        await apiRequest<void>(`/api/guest-notifications/${token}/subscriptions`, {
          method: 'PATCH',
          body: JSON.stringify({ endpoint: subscription.endpoint, enabled: false }),
        })
        setPushEnabled(false)
        setNotice('تم إيقاف إشعارات حالة هذا الحجز على هذا الجهاز.')
      }
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحديث إشعارات الحجز.'))
    } finally {
      setBusy(false)
    }
  }

  const markRead = async (item: GuestNotificationItem) => {
    if (!item.readAt) {
      await apiRequest<void>(`/api/guest-notifications/${token}/${item.id}/read`, { method: 'PATCH' })
      await load()
    }
    navigate(item.targetPath === '/guest-notifications' ? `/guest-notifications/${token}` : item.targetPath)
  }

  const markAllRead = async () => {
    await apiRequest<void>(`/api/guest-notifications/${token}/read-all`, { method: 'POST' })
    await load()
  }

  const savedBookings = readGuestBookingLinks()

  return (
    <section className="notification-center notification-center--guest">
      <header className="notification-center-heading">
        <div>
          <span className="notification-center-eyebrow">متابعة الحجز</span>
          <h1>إشعارات حجزك</h1>
          <p>تابع حالة طلبك من هذا الرابط بدون إنشاء حساب.</p>
        </div>
        <div className="notification-center-actions">
          {savedBookings.length > 1 && (
            <label className="guest-booking-selector">
              <span>حجوزاتك</span>
              <select aria-label="اختر الحجز" value={token} onChange={(event) => {
                navigate(`/guest-notifications/${encodeURIComponent(event.target.value)}`)
              }}>
                {savedBookings.slice().reverse().map((booking, index) => (
                  <option key={booking.token} value={booking.token}>
                    {booking.bookingId ? `حجز ${booking.bookingId.slice(0, 8)}` : `حجز ${savedBookings.length - index}`}
                  </option>
                ))}
              </select>
            </label>
          )}
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

      <section className="notification-settings notification-settings--compact">
        <div className="notification-settings-title">
          <BellRing size={19} />
          <div><h2>إشعارات الهاتف</h2><p>فعّلها لتصلك تحديثات الحجز حتى عند إغلاق الموقع.</p></div>
        </div>
        <button className="notification-settings-toggle" type="button" onClick={() => void togglePush()} disabled={!pushSupported || busy || !configuration?.enabled}>
          {busy ? 'جارٍ التحديث...' : pushEnabled ? 'إيقاف على هذا الجهاز' : 'تفعيل إشعارات الحجز'}
        </button>
      </section>

      <section className="notification-feed" aria-label="إشعارات الحجز">
        <h2>آخر التحديثات</h2>
        {loading ? <p className="notification-empty" role="status">جارٍ تحميل الإشعارات...</p>
          : center?.content.length ? center.content.map((item) => (
            <button
              type="button"
              className={`notification-feed-item${item.readAt ? '' : ' notification-feed-item--unread'}${item.id === notificationIdFromQuery ? ' notification-feed-item--highlighted' : ''}`}
              key={item.id}
              onClick={() => void markRead(item)}
            >
              <span className="notification-feed-icon"><Bell size={18} /></span>
              <span className="notification-feed-copy"><strong>{item.title}</strong><span>{item.body}</span><small>{formatShopDateTime(item.createdAt)}</small></span>
              {!item.readAt && <span className="notification-unread-dot" aria-label="غير مقروء" />}
            </button>
          )) : <p className="notification-empty">لا توجد تحديثات لهذا الحجز حالياً.</p>}
      </section>
    </section>
  )
}

export default GuestNotificationPage
