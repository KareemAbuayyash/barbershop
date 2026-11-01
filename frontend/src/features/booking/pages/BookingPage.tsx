import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ArrowLeft } from 'lucide-react'
import { Link } from 'react-router-dom'
import PageIntro from '../../../components/ui/PageIntro'
import BookingModeCards from '../components/BookingModeCards'
import { apiRequest, getErrorMessage, type BookingMode, type BookingSettings, type GuestBookingResult, type Service } from '../../../lib/api'
import { getShopDate, shopLocalDateTimeToIso } from '../../../lib/shop-time'

function BookingPage() {
  const [services, setServices] = useState<Service[]>([])
  const [settings, setSettings] = useState<BookingSettings | null>(null)
  const [selectedMode, setSelectedMode] = useState<BookingMode | undefined>()
  const [isLoading, setIsLoading] = useState(true)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState('')
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
    let startsAt: string | null = null
    try {
      if (preferredTime && (selectedMode === 'EXACT_APPOINTMENT' || selectedMode === 'CONFIRMATION_REQUEST')) {
        startsAt = shopLocalDateTimeToIso(preferredTime)
      }
    } catch (cause) {
      setError(getErrorMessage(cause, 'تحقق من التاريخ والوقت المدخلين.'))
      setIsSubmitting(false)
      return
    }

    const requestBody = {
      bookingMode: selectedMode,
      serviceId: String(formData.get('serviceId')),
      customerName: String(formData.get('customerName')).trim(),
      customerPhone: String(formData.get('customerPhone')).trim() || null,
      startsAt,
      queueDate: selectedMode === 'DAY_QUEUE' ? String(formData.get('queueDate')) : null,
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
                setError('')
                setResult(null)
              }}
            />
            {selectedMode && (
              <form className="booking-form site-form" onSubmit={(event) => void handleSubmit(event)}>
                <h2>تفاصيل الحجز</h2>
                <label htmlFor="booking-service">الخدمة</label>
                <select id="booking-service" name="serviceId" required defaultValue="">
                  <option value="" disabled>اختر الخدمة</option>
                  {services.map((service) => (
                    <option key={service.id} value={service.id}>
                      {service.name} — {service.price} ₪، {service.durationMinutes} دقيقة
                    </option>
                  ))}
                </select>

                <label htmlFor="booking-customer-name">الاسم</label>
                <input id="booking-customer-name" name="customerName" autoComplete="name" maxLength={150} required />
                <label htmlFor="booking-customer-phone">رقم الهاتف (اختياري)</label>
                <input id="booking-customer-phone" name="customerPhone" type="tel" autoComplete="tel" maxLength={32} />

                {selectedMode === 'EXACT_APPOINTMENT' && (
                  <>
                    <label htmlFor="booking-starts-at">التاريخ والوقت المطلوبان</label>
                    <input id="booking-starts-at" name="startsAt" type="datetime-local" required />
                    <p className="field-help">يُفسّر الوقت حسب توقيت المحل (Asia/Hebron).</p>
                  </>
                )}
                {selectedMode === 'CONFIRMATION_REQUEST' && (
                  <>
                    <label htmlFor="booking-preferred-time">وقت مفضّل (اختياري)</label>
                    <input id="booking-preferred-time" name="startsAt" type="datetime-local" />
                    <p className="field-help">سيؤكد الحلاق الموعد أو يقترح وقتاً مناسباً.</p>
                  </>
                )}
                {selectedMode === 'DAY_QUEUE' && (
                  <>
                    <label htmlFor="booking-queue-date">اليوم المطلوب</label>
                    <input id="booking-queue-date" name="queueDate" type="date" min={getShopDate()} required />
                  </>
                )}
                {selectedMode === 'SAME_DAY_QUEUE' && (
                  <p className="field-help">سيُضاف طلبك إلى قائمة انتظار اليوم حسب ترتيب وصوله.</p>
                )}

                <label htmlFor="booking-notes">ملاحظة (اختياري)</label>
                <textarea id="booking-notes" name="notes" rows={3} maxLength={2000} />
                <button className="button button--primary form-submit" type="submit" disabled={isSubmitting}>
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
