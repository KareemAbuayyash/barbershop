import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ArrowLeft, BellRing, X } from 'lucide-react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router-dom'
import PageIntro from '../../../components/ui/PageIntro'
import { useAuth } from '../../auth/useAuth'
import BookingModeCards from '../components/BookingModeCards'
import { apiRequest, getErrorMessage, type BookingMode, type BookingSettings, type GuestBookingResult, type Service } from '../../../lib/api'
import { rememberGuestBookingLink } from '../../notifications/guest-booking-links'

function createIdempotencyKey(): string {
  const cryptoApi = globalThis.crypto
  if (typeof cryptoApi?.randomUUID === 'function') {
    return cryptoApi.randomUUID()
  }
  return `booking-${Date.now()}-${Math.random().toString(36).slice(2)}`
}
import { getShopDate } from '../../../lib/shop-time'

interface BookingAvailability {
  open: boolean
  slots: Array<{ startsAt: string; localTime: string }>
}

interface CustomerProfile {
  name: string
  phoneNumber: string
}

function applicationKey(value: string) {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/')
    .padEnd(Math.ceil(value.length / 4) * 4, '=')
  return Uint8Array.from(window.atob(normalized), (character) => character.charCodeAt(0))
}

function subscriptionUsesApplicationKey(subscription: PushSubscription, publicKey: string) {
  const currentKey = subscription.options.applicationServerKey
  if (!currentKey) return false
  const current = new Uint8Array(currentKey)
  const expected = applicationKey(publicKey)
  return current.length === expected.length && current.every((byte, index) => byte === expected[index])
}

async function subscribeGuestBooking(token: string) {
  const configuration = await apiRequest<{ enabled: boolean; publicKey: string }>(
    `/api/guest-notifications/${token}/configuration`,
  )
  if (!configuration.enabled || !configuration.publicKey) {
    throw new Error('إشعارات الحجز غير مهيأة على الخادم حالياً.')
  }

  const registration = await navigator.serviceWorker.ready
  let subscription = await registration.pushManager.getSubscription()
  if (subscription && !subscriptionUsesApplicationKey(subscription, configuration.publicKey)) {
    const removed = await subscription.unsubscribe()
    if (!removed) throw new Error('تعذر تجديد اشتراك الإشعارات القديم على هذا الهاتف.')
    subscription = null
  }
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
}

function BookingPage() {
  const { user, isLoading: authLoading } = useAuth()
  const isCustomer = user?.authorities.includes('ROLE_CUSTOMER') ?? false
  const [services, setServices] = useState<Service[]>([])
  const [settings, setSettings] = useState<BookingSettings | null>(null)
  const [selectedMode, setSelectedMode] = useState<BookingMode | undefined>()
  const [selectedServiceId, setSelectedServiceId] = useState('')
  const [scheduleDate, setScheduleDate] = useState(getShopDate)
  const [preferredDateSelected, setPreferredDateSelected] = useState(false)
  const [availability, setAvailability] = useState<BookingAvailability | null>(null)
  const [loadingAvailability, setLoadingAvailability] = useState(false)
  const [selectedSlot, setSelectedSlot] = useState('')
  const [isLoading, setIsLoading] = useState(true)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [profileError, setProfileError] = useState('')
  const [customerProfile, setCustomerProfile] = useState<CustomerProfile | null>(null)
  const [loadingProfile, setLoadingProfile] = useState(false)
  const [bookingForSomeoneElse, setBookingForSomeoneElse] = useState(false)
  const [customerName, setCustomerName] = useState('')
  const [customerPhone, setCustomerPhone] = useState('')
  const [result, setResult] = useState<GuestBookingResult | null>(null)
  const [guestPushBusy, setGuestPushBusy] = useState(false)
  const [guestPushEnabled, setGuestPushEnabled] = useState(false)
  const [guestPushError, setGuestPushError] = useState('')
  const [guestPushNotice, setGuestPushNotice] = useState('')
  const idempotency = useRef<{ fingerprint: string; key: string } | null>(null)

  useEffect(() => {
    let active = true
    void Promise.all([
      apiRequest<Service[]>('/api/services'),
      apiRequest<BookingSettings>('/api/booking-settings'),
    ])
      .then(([availableServices, currentSettings]) => {
        if (active) {
          setServices(availableServices)
          setSettings(currentSettings)
        }
      })
      .catch((cause: unknown) => {
        if (active) setError(getErrorMessage(cause, 'تعذر تحميل بيانات الحجز. حاول لاحقاً.'))
      })
      .finally(() => {
        if (active) setIsLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  useEffect(() => {
    if (!isCustomer || !user) {
      setCustomerProfile(null)
      setCustomerName('')
      setCustomerPhone('')
      setBookingForSomeoneElse(false)
      setLoadingProfile(false)
      setProfileError('')
      return
    }
    let active = true
    setCustomerProfile(null)
    setCustomerName('')
    setCustomerPhone('')
    setLoadingProfile(true)
    setProfileError('')
    void apiRequest<CustomerProfile>('/api/customer/profile')
      .then((profile) => {
        if (active) {
          setCustomerProfile(profile)
          setCustomerName(profile.name)
          setCustomerPhone(profile.phoneNumber)
        }
      })
      .catch((cause: unknown) => {
        if (active) setProfileError(getErrorMessage(cause, 'تعذر تعبئة بيانات حسابك. أدخل بياناتك يدوياً.'))
      })
      .finally(() => {
        if (active) setLoadingProfile(false)
      })
    return () => { active = false }
  }, [isCustomer, user])

  useEffect(() => {
    if (!bookingForSomeoneElse && customerProfile) {
      setCustomerName(customerProfile.name)
      setCustomerPhone(customerProfile.phoneNumber)
    }
  }, [bookingForSomeoneElse, customerProfile])

  useEffect(() => {
    if (!selectedMode) {
      setLoadingAvailability(false)
      return
    }
    const usesAppointmentSlots = selectedMode === 'EXACT_APPOINTMENT'
      || (selectedMode === 'CONFIRMATION_REQUEST' && preferredDateSelected)
    const needsAvailability = usesAppointmentSlots
      || selectedMode === 'DAY_QUEUE'
    if (!needsAvailability) {
      setAvailability(null)
      setLoadingAvailability(false)
      return
    }

    let active = true
    const date = scheduleDate
    const params = new URLSearchParams({ date })
    if (usesAppointmentSlots && selectedServiceId) params.set('serviceId', selectedServiceId)
    setLoadingAvailability(true)
    void apiRequest<BookingAvailability>(`/api/bookings/availability?${params}`)
      .then((result) => { if (active) setAvailability(result) })
      .catch((cause: unknown) => {
        if (active) setError(getErrorMessage(cause, 'تعذر تحميل أوقات العمل والمواعيد المتاحة.'))
      })
      .finally(() => { if (active) setLoadingAvailability(false) })
    return () => { active = false }
  }, [preferredDateSelected, scheduleDate, selectedMode, selectedServiceId])

  const enabledModes = settings
    ? [
        settings.exactAppointmentEnabled && 'EXACT_APPOINTMENT',
        settings.confirmationRequestEnabled && 'CONFIRMATION_REQUEST',
        settings.dayQueueEnabled && 'DAY_QUEUE',
      ].filter((mode): mode is BookingMode => Boolean(mode))
    : []
  const bookingClosed = settings?.newBookingsDisabled ?? false
  const selectedDateNeedsAvailability = selectedMode === 'EXACT_APPOINTMENT'
    || selectedMode === 'DAY_QUEUE'
    || (selectedMode === 'CONFIRMATION_REQUEST' && preferredDateSelected)

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!selectedMode) return
    setError('')
    setResult(null)
    setIsSubmitting(true)

    try {
      const formData = new FormData(event.currentTarget)
      const preferredTime = String(formData.get('startsAt') ?? '')
      const startsAt = preferredTime || null

      const requestBody = {
        bookingMode: selectedMode,
        serviceId: selectedServiceId,
        customerName: String(formData.get('customerName')).trim(),
        customerPhone: String(formData.get('customerPhone')).trim() || null,
        bookForSomeoneElse: isCustomer && bookingForSomeoneElse,
        startsAt,
        preferredDate: selectedMode === 'CONFIRMATION_REQUEST' && preferredDateSelected
          ? scheduleDate
          : null,
        queueDate: selectedMode === 'DAY_QUEUE' ? scheduleDate : null,
        notes: String(formData.get('notes')).trim() || null,
      }
      const fingerprint = JSON.stringify(requestBody)
      if (!idempotency.current || idempotency.current.fingerprint !== fingerprint) {
        idempotency.current = { fingerprint, key: createIdempotencyKey() }
      }

      const created = await apiRequest<GuestBookingResult>('/api/bookings', {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotency.current.key },
        body: JSON.stringify(requestBody),
      })
      if (created.guestAccessToken) {
        rememberGuestBookingLink(created.bookingId, created.guestAccessToken)
      }
      setGuestPushEnabled(false)
      setGuestPushError('')
      setGuestPushNotice('')
      setResult(created)
      const autoSubscribeGuest = Boolean(created.guestAccessToken)
        && window.localStorage.getItem('barber-house-guest-push-opt-in') === 'true'
        && 'Notification' in window && Notification.permission === 'granted'
      if (created.guestAccessToken && autoSubscribeGuest) {
        setGuestPushBusy(true)
        void subscribeGuestBooking(created.guestAccessToken)
          .then(() => {
            setGuestPushEnabled(true)
            setGuestPushNotice('تم ربط هذا الحجز بإشعارات هذا الهاتف تلقائياً.')
          })
          .catch((cause: unknown) => {
            setGuestPushError(getErrorMessage(cause, 'تعذر ربط إشعارات هذا الحجز.'))
          })
          .finally(() => setGuestPushBusy(false))
      }
      setSelectedMode(undefined)
      idempotency.current = null
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر إرسال الحجز. تحقق من البيانات وحاول مرة أخرى.'))
    } finally {
      setIsSubmitting(false)
    }
  }

  const enableGuestPush = async () => {
    const token = result?.guestAccessToken
    setGuestPushError('')
    setGuestPushNotice('')
    if (!token) return
    if (!('serviceWorker' in navigator) || !('PushManager' in window)
      || !('Notification' in window) || !window.isSecureContext) {
      setGuestPushError('هذا المتصفح أو الاتصال الحالي لا يدعم إشعارات الهاتف.')
      return
    }

    setGuestPushBusy(true)
    try {
      // Request permission directly from this click before any network awaits.
      const permission = Notification.permission === 'granted'
        ? 'granted'
        : await Notification.requestPermission()
      if (permission !== 'granted') throw new Error('لم تسمح بإشعارات حالة الحجز على هذا الهاتف.')

      await subscribeGuestBooking(token)
      window.localStorage.setItem('barber-house-guest-push-opt-in', 'true')
      setGuestPushEnabled(true)
      setGuestPushNotice('تم تفعيل إشعارات حالة الحجز على هذا الهاتف. ستصلك التحديثات حتى بعد إغلاق الموقع.')
    } catch (cause) {
      setGuestPushError(getErrorMessage(cause, 'تعذر تفعيل إشعارات حالة الحجز على هذا الهاتف.'))
    } finally {
      setGuestPushBusy(false)
    }
  }

  return (
    <div className="page-content booking-page">
      <PageIntro
        eyebrow="طرق الحجز"
        title="موعدك، بالطريقة التي تناسبك"
        description="اختر طريقة الحجز وأرسل طلبك مباشرة إلى المحل."
      />
      <section className="container page-section booking-choice" aria-labelledby="booking-choice-title">
        <h2 id="booking-choice-title" className="visually-hidden">اختر طريقة الحجز</h2>
        {isLoading ? (
          <p className="api-state" role="status">جارٍ تحميل خيارات الحجز...</p>
        ) : error && !settings ? (
          <p className="api-state api-state--error" role="alert">{error}</p>
        ) : bookingClosed ? (
          <p className="api-state">الحجز الإلكتروني مغلق حالياً. يرجى التواصل مع المحل.</p>
        ) : services.length === 0 ? (
          <p className="api-state">لا توجد خدمات متاحة للحجز حالياً. يرجى التواصل مع المحل.</p>
        ) : enabledModes.length === 0 ? (
          <p className="api-state">لم يتم تفعيل طرق الحجز بعد. يرجى التواصل مع المحل.</p>
        ) : (
          <>
            <BookingModeCards
              enabledModes={enabledModes}
              selectedMode={selectedMode}
              onSelect={(mode) => {
                setSelectedMode(mode)
                setAvailability(null)
                setSelectedSlot('')
                setPreferredDateSelected(false)
                setLoadingAvailability(
                  mode === 'DAY_QUEUE'
                    || (mode === 'EXACT_APPOINTMENT' && Boolean(selectedServiceId)),
                )
                setError('')
                setResult(null)
              }}
            />
            {selectedMode && createPortal(
              <div className="booking-modal-backdrop" role="presentation"
                onMouseDown={(event) => {
                  if (event.target === event.currentTarget) setSelectedMode(undefined)
                }}>
              <section className="booking-modal" role="dialog" aria-modal="true"
                aria-labelledby="booking-form-title" dir="rtl">
              <div className="booking-modal-heading">
                <div><span className="booking-modal-eyebrow">تم اختيار طريقة الحجز</span>
                  <h2 id="booking-form-title">تفاصيل الحجز</h2></div>
                <button type="button" className="booking-modal-close" aria-label="إغلاق"
                  onClick={() => setSelectedMode(undefined)}><X size={19} /></button>
              </div>
              <form className="booking-form site-form" onSubmit={(event) => void handleSubmit(event)}>
                <label htmlFor="booking-service">الخدمة</label>
                <select id="booking-service" name="serviceId" required value={selectedServiceId}
                  onChange={(event) => {
                    setSelectedServiceId(event.target.value)
                    setAvailability(null)
                    setSelectedSlot('')
                    setLoadingAvailability(true)
                  }}>
                  <option value="" disabled>اختر الخدمة</option>
                  {services.map((service) => (
                    <option key={service.id} value={service.id}>
                      {service.name} — {service.price} ₪، {service.durationMinutes} دقيقة
                    </option>
                  ))}
                </select>

                {isCustomer && (
                  <>
                    {loadingProfile && <p className="field-help" role="status">جارٍ تحميل بيانات حسابك...</p>}
                    {profileError && <p className="form-message" role="alert">{profileError}</p>}
                    <label className="booking-other-person">
                      <input type="checkbox" checked={bookingForSomeoneElse} disabled={loadingProfile}
                        onChange={(event) => {
                          const bookForOther = event.target.checked
                          setBookingForSomeoneElse(bookForOther)
                          if (bookForOther) {
                            setCustomerName('')
                            setCustomerPhone('')
                          } else if (customerProfile) {
                            setCustomerName(customerProfile.name)
                            setCustomerPhone(customerProfile.phoneNumber)
                          }
                        }} />
                      أحجز لشخص آخر
                    </label>
                  </>
                )}
                <label htmlFor="booking-customer-name">الاسم</label>
                <input id="booking-customer-name" name="customerName" autoComplete="name" maxLength={150}
                  value={customerName} onChange={(event) => setCustomerName(event.target.value)} required />
                <label htmlFor="booking-customer-phone">رقم الهاتف (اختياري)</label>
                <input id="booking-customer-phone" name="customerPhone" type="tel" autoComplete="tel" maxLength={32}
                  value={customerPhone} onChange={(event) => setCustomerPhone(event.target.value)} />

                {selectedMode === 'EXACT_APPOINTMENT' && (
                  <>
                    <label htmlFor="booking-date">تاريخ الموعد</label>
                    <input id="booking-date" type="date" min={getShopDate()} value={scheduleDate}
                      onChange={(event) => {
                        setScheduleDate(event.target.value)
                        setAvailability(null)
                        setSelectedSlot('')
                        setLoadingAvailability(true)
                      }}
                      onClick={(event) => event.currentTarget.showPicker?.()} required />
                    <label htmlFor="booking-starts-at">الوقت المتاح</label>
                    <select id="booking-starts-at" name="startsAt" value={selectedSlot}
                      onChange={(event) => setSelectedSlot(event.target.value)} required>
                      <option value="" disabled>اختر موعداً متاحاً</option>
                      {availability?.slots.map((slot) => (
                        <option value={slot.startsAt} key={slot.startsAt}>{slot.localTime}</option>
                      ))}
                    </select>
                  </>
                )}
                {selectedMode === 'CONFIRMATION_REQUEST' && (
                  <>
                    <label htmlFor="booking-preferred-date">تاريخ مفضّل (اختياري)</label>
                    <input id="booking-preferred-date" type="date" min={getShopDate()}
                      value={preferredDateSelected ? scheduleDate : ''}
                      onChange={(event) => {
                        setScheduleDate(event.target.value)
                        setPreferredDateSelected(Boolean(event.target.value))
                        setAvailability(null)
                        setSelectedSlot('')
                      }}
                      onClick={(event) => event.currentTarget.showPicker?.()} />
                    <label htmlFor="booking-preferred-time">وقت مفضّل (اختياري)</label>
                    <select id="booking-preferred-time" name="startsAt" value={selectedSlot}
                      onChange={(event) => setSelectedSlot(event.target.value)}>
                      <option value="">لا يوجد وقت مفضّل</option>
                      {availability?.slots.map((slot) => (
                        <option value={slot.startsAt} key={slot.startsAt}>{slot.localTime}</option>
                      ))}
                    </select>
                    {!preferredDateSelected && <p className="field-help">حدد التاريخ المفضّل أولاً، ثم اختر الخدمة لعرض الأوقات الممكنة.</p>}
                    {preferredDateSelected && !selectedServiceId
                      && <p className="field-help">اختر الخدمة أولاً لعرض الأوقات المتاحة حسب مدة الخدمة.</p>}
                    {preferredDateSelected && selectedServiceId && loadingAvailability
                      && <p className="field-help" role="status">جارٍ تحميل الأوقات الممكنة...</p>}
                    <p className="field-help">سيؤكد الحلاق الموعد أو يقترح وقتاً مناسباً.</p>
                  </>
                )}
                {selectedMode === 'DAY_QUEUE' && (
                  <>
                    <label htmlFor="booking-queue-date">اليوم المطلوب</label>
                    <input id="booking-queue-date" name="queueDate" type="date" min={getShopDate()}
                      value={scheduleDate} onChange={(event) => {
                        setScheduleDate(event.target.value)
                        setAvailability(null)
                        setLoadingAvailability(true)
                      }}
                      onClick={(event) => event.currentTarget.showPicker?.()} required />
                  </>
                )}
                {loadingAvailability && (selectedMode !== 'CONFIRMATION_REQUEST' || preferredDateSelected)
                  && <p className="field-help" role="status">جارٍ التحقق من ساعات العمل...</p>}
                {!loadingAvailability && availability && !availability.open
                  && <p className="form-message" role="alert">المحل مغلق في هذا اليوم.</p>}
                {!loadingAvailability && availability?.open
                  && (selectedMode === 'EXACT_APPOINTMENT' || selectedMode === 'CONFIRMATION_REQUEST')
                  && selectedServiceId
                  && availability.slots.length === 0
                  && <p className="field-help">{selectedMode === 'CONFIRMATION_REQUEST'
                    ? 'يمكنك إرسال الطلب دون وقت مفضّل، وسيقترح الحلاق موعداً مناسباً.'
                    : 'لا توجد مواعيد متاحة لهذه الخدمة في هذا اليوم.'}</p>}

                <label htmlFor="booking-notes">ملاحظة (اختياري)</label>
                <textarea id="booking-notes" name="notes" rows={3} maxLength={2000} />
                <button className="button button--primary form-submit" type="submit"
                  disabled={authLoading || loadingProfile || isSubmitting
                    || (selectedDateNeedsAvailability && (loadingAvailability || !availability?.open))
                    || (selectedMode === 'EXACT_APPOINTMENT' && !selectedSlot)}>
                  {isSubmitting ? 'جارٍ إرسال الطلب...' : 'إرسال طلب الحجز'}
                </button>
                {error && <p className="form-message" role="alert">{error}</p>}
              </form>
              </section>
              </div>,
              document.body,
            )}
          </>
        )}
        {result && createPortal(
          <div className="booking-modal-backdrop booking-success-backdrop" role="presentation">
            <section className="booking-modal booking-success-modal" role="alertdialog" aria-modal="true"
              aria-labelledby="booking-success-title" dir="rtl">
              <button type="button" className="booking-modal-close" aria-label="إغلاق"
                onClick={() => setResult(null)}><X size={19} /></button>
              <span className="booking-success-mark" aria-hidden="true">✓</span>
              <h2 id="booking-success-title">تم استلام طلبك بنجاح</h2>
              <p>رقم الطلب: <strong dir="ltr">{result.bookingId.slice(0, 8)}</strong></p>
              {result.queuePosition && <p>رقمك في قائمة الانتظار: <strong>{result.queuePosition}</strong></p>}
              {result.guestAccessToken && (
                <div className="booking-guest-notifications">
                  <p>فعّل الإشعارات على هذا الهاتف لتصلك رسالة عند تأكيد الموعد أو تعديله.</p>
                  <button className="button button--primary form-submit" type="button"
                    onClick={() => void enableGuestPush()} disabled={guestPushBusy || guestPushEnabled}>
                    <BellRing size={17} />
                    {guestPushBusy ? 'جارٍ تفعيل الإشعارات...' : guestPushEnabled ? 'إشعارات الحجز مفعّلة' : 'تفعيل إشعارات الحجز على هذا الهاتف'}
                  </button>
                  {guestPushError && <p className="form-message" role="alert">{guestPushError}</p>}
                  {guestPushNotice && <p className="field-help" role="status">{guestPushNotice}</p>}
                  <Link className="text-link" to={`/guest-notifications/${encodeURIComponent(result.guestAccessToken)}`}>
                    فتح مركز متابعة الحجز
                  </Link>
                </div>
              )}
              <button className="button button--primary form-submit" type="button"
                onClick={() => setResult(null)}>موافق</button>
            </section>
          </div>,
          document.body,
        )}
        <Link className="text-link booking-back-link" to="/">العودة إلى الرئيسية <ArrowLeft size={15} aria-hidden="true" /></Link>
      </section>
    </div>
  )
}

export default BookingPage
