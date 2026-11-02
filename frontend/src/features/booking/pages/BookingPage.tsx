import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ArrowLeft } from 'lucide-react'
import { Link } from 'react-router-dom'
import PageIntro from '../../../components/ui/PageIntro'
import { useAuth } from '../../auth/useAuth'
import BookingModeCards from '../components/BookingModeCards'
import { apiRequest, getErrorMessage, type BookingMode, type BookingSettings, type GuestBookingResult, type Service } from '../../../lib/api'
import { getShopDate } from '../../../lib/shop-time'

interface BookingAvailability {
  open: boolean
  slots: Array<{ startsAt: string; localTime: string }>
}

interface CustomerProfile {
  name: string
  phoneNumber: string
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
      || selectedMode === 'SAME_DAY_QUEUE'
    if (!needsAvailability || (usesAppointmentSlots && !selectedServiceId)) {
      setAvailability(null)
      setLoadingAvailability(false)
      return
    }

    let active = true
    const date = selectedMode === 'SAME_DAY_QUEUE' ? getShopDate() : scheduleDate
    const params = new URLSearchParams({ date })
    if (usesAppointmentSlots) params.set('serviceId', selectedServiceId)
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
        settings.sameDayQueueEnabled && 'SAME_DAY_QUEUE',
      ].filter((mode): mode is BookingMode => Boolean(mode))
    : []
  const bookingClosed = settings?.newBookingsDisabled ?? false

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!selectedMode) return
    setError('')
    setResult(null)
    setIsSubmitting(true)

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
      queueDate: selectedMode === 'DAY_QUEUE' ? scheduleDate : null,
      notes: String(formData.get('notes')).trim() || null,
    }
    const fingerprint = JSON.stringify(requestBody)
    if (!idempotency.current || idempotency.current.fingerprint !== fingerprint) {
      idempotency.current = { fingerprint, key: crypto.randomUUID() }
    }

    try {
      const created = await apiRequest<GuestBookingResult>('/api/bookings', {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotency.current.key },
        body: JSON.stringify(requestBody),
      })
      setResult(created)
      idempotency.current = null
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر إرسال الحجز. تحقق من البيانات وحاول مرة أخرى.'))
    } finally {
      setIsSubmitting(false)
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
                  mode === 'DAY_QUEUE' || mode === 'SAME_DAY_QUEUE'
                    || (mode === 'EXACT_APPOINTMENT' && Boolean(selectedServiceId)),
                )
                setError('')
                setResult(null)
              }}
            />
            {selectedMode && (
              <form className="booking-form site-form" onSubmit={(event) => void handleSubmit(event)}>
                <h2>تفاصيل الحجز</h2>
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
                {selectedMode === 'SAME_DAY_QUEUE' && (
                  <p className="field-help">سيُضاف طلبك إلى قائمة انتظار اليوم حسب ترتيب وصوله.</p>
                )}
                {loadingAvailability && selectedMode !== 'CONFIRMATION_REQUEST'
                  && <p className="field-help" role="status">جارٍ التحقق من ساعات العمل...</p>}
                {!loadingAvailability && availability && !availability.open
                  && selectedMode !== 'CONFIRMATION_REQUEST'
                  && <p className="form-message" role="alert">المحل مغلق في هذا اليوم.</p>}
                {!loadingAvailability && availability?.open
                  && (selectedMode === 'EXACT_APPOINTMENT' || selectedMode === 'CONFIRMATION_REQUEST')
                  && availability.slots.length === 0
                  && <p className="field-help">{selectedMode === 'CONFIRMATION_REQUEST'
                    ? 'يمكنك إرسال الطلب دون وقت مفضّل، وسيقترح الحلاق موعداً مناسباً.'
                    : 'لا توجد مواعيد متاحة لهذه الخدمة في هذا اليوم.'}</p>}

                <label htmlFor="booking-notes">ملاحظة (اختياري)</label>
                <textarea id="booking-notes" name="notes" rows={3} maxLength={2000} />
                <button className="button button--primary form-submit" type="submit"
                  disabled={authLoading || loadingProfile || isSubmitting
                    || ((selectedMode === 'EXACT_APPOINTMENT'
                      || selectedMode === 'DAY_QUEUE' || selectedMode === 'SAME_DAY_QUEUE')
                      && (loadingAvailability || !availability?.open))
                    || (selectedMode === 'EXACT_APPOINTMENT' && !selectedSlot)}>
                  {isSubmitting ? 'جارٍ إرسال الطلب...' : 'إرسال طلب الحجز'}
                </button>
                {error && <p className="form-message" role="alert">{error}</p>}
                {result && (
                  <p className="form-message" role="status">
                    تم استلام طلبك بنجاح. رقم الطلب: {result.bookingId.slice(0, 8)}.
                    {result.queuePosition ? ` رقمك في القائمة: ${result.queuePosition}.` : ''}
                  </p>
                )}
              </form>
            )}
          </>
        )}
        <Link className="text-link booking-back-link" to="/">العودة إلى الرئيسية <ArrowLeft size={15} aria-hidden="true" /></Link>
      </section>
    </div>
  )
}

export default BookingPage
