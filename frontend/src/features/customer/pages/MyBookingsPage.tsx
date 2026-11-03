import { useCallback, useEffect, useState } from 'react'
import { CalendarClock, CalendarDays, X } from 'lucide-react'
import { Link } from 'react-router-dom'
import PageIntro from '../../../components/ui/PageIntro'
import { apiRequest, getErrorMessage, type CustomerBooking } from '../../../lib/api'
import { formatShopDateTime, getShopDate } from '../../../lib/shop-time'
import { useI18n } from '../../../lib/i18n'

const bookingModes: Record<CustomerBooking['bookingMode'], string> = {
  EXACT_APPOINTMENT: 'موعد محدد',
  CONFIRMATION_REQUEST: 'طلب موعد',
  DAY_QUEUE: 'قائمة يومية',
  SAME_DAY_QUEUE: 'دور اليوم',
  LEGACY: 'حجز سابق',
}

const bookingStatuses: Record<CustomerBooking['status'], string> = {
  PENDING: 'بانتظار التأكيد',
  CONFIRMED: 'مؤكد',
  COMPLETED: 'مكتمل',
  CANCELLED: 'ملغي',
}

function MyBookingsPage() {
  const { t, language } = useI18n()
  const [actionBooking, setActionBooking] = useState<{ booking: CustomerBooking; action: 'cancel' | 'reschedule' } | null>(null)
  const [rescheduleDate, setRescheduleDate] = useState(getShopDate())
  const [rescheduleTime, setRescheduleTime] = useState('')
  const [rescheduleReason, setRescheduleReason] = useState('')
  const [availableSlots, setAvailableSlots] = useState<Array<{ startsAt: string; localTime: string }>>([])
  const [loadingSlots, setLoadingSlots] = useState(false)
  const [bookings, setBookings] = useState<CustomerBooking[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busyBookingId, setBusyBookingId] = useState('')

  useEffect(() => {
    if (actionBooking?.action !== 'reschedule') return
    let active = true
    setLoadingSlots(true)
    setRescheduleTime('')
    const params = new URLSearchParams({ date: rescheduleDate, serviceId: actionBooking.booking.serviceId })
    void apiRequest<{ open: boolean; slots: Array<{ startsAt: string; localTime: string }> }>(`/api/bookings/availability?${params}`)
      .then((result) => { if (active) setAvailableSlots(result.slots) })
      .catch(() => { if (active) setAvailableSlots([]) })
      .finally(() => { if (active) setLoadingSlots(false) })
    return () => { active = false }
  }, [actionBooking, rescheduleDate])

  const loadBookings = useCallback(async () => {
    try {
      const result = await apiRequest<CustomerBooking[]>('/api/customer/bookings')
      setBookings(result)
      setError('')
    } catch (cause) {
      setError(t(getErrorMessage(cause, 'تعذر تحميل حجوزاتك. حاول مرة أخرى.')))
    } finally {
      setIsLoading(false)
    }
  }, [t])

  const runBookingAction = (booking: CustomerBooking, action: 'cancel' | 'reschedule') => {
    setActionBooking({ booking, action })
    setRescheduleDate(getShopDate())
    setRescheduleTime('')
    setRescheduleReason('')
  }

  const submitBookingAction = async () => {
    if (!actionBooking) return
    const { booking, action } = actionBooking
    const isReschedule = action === 'reschedule'
    setBusyBookingId(booking.id)
    setError('')
    setNotice('')
    try {
      await apiRequest(`/api/customer/bookings/${booking.id}/${isReschedule ? 'reschedule-request' : 'cancel'}`, {
        method: 'PATCH',
        ...(isReschedule ? { body: JSON.stringify({ preferredDate: rescheduleDate, startsAt: rescheduleTime || null, reason: rescheduleReason.trim() || null }) } : {}),
      })
      setNotice(t(isReschedule ? 'تم إرسال طلب التأجيل للمحل.' : 'تم إلغاء الحجز.'))
      setActionBooking(null)
      await loadBookings()
    } catch (cause) {
      setError(t(getErrorMessage(cause, isReschedule ? 'تعذر إرسال طلب التأجيل.' : 'تعذر إلغاء الحجز.')))
    } finally {
      setBusyBookingId('')
    }
  }

  useEffect(() => {
    const refreshWhenVisible = () => {
      if (document.visibilityState === 'visible') void loadBookings()
    }
    const onServiceWorkerMessage = (event: MessageEvent) => {
      if (event.data?.type === 'barbershop:notification-received') void loadBookings()
    }
    void loadBookings()
    window.addEventListener('focus', refreshWhenVisible)
    document.addEventListener('visibilitychange', refreshWhenVisible)
    navigator.serviceWorker?.addEventListener('message', onServiceWorkerMessage)
    return () => {
      window.removeEventListener('focus', refreshWhenVisible)
      document.removeEventListener('visibilitychange', refreshWhenVisible)
      navigator.serviceWorker?.removeEventListener('message', onServiceWorkerMessage)
    }
  }, [loadBookings])

  return (
    <div className="page-content customer-page">
      <PageIntro
        eyebrow="مساحة العميل"
        title="حجوزاتي"
        description="تابع حالة حجوزاتك ومواعيدك في المحل."
      />
      <section className="container customer-bookings" aria-label={t('قائمة حجوزاتي')}>
        {notice && <p className="api-state" role="status">{notice}</p>}
        {isLoading ? (
          <p className="api-state" role="status">{t('جارٍ تحميل حجوزاتك...')}</p>
        ) : error ? (
          <p className="api-state api-state--error" role="alert">{t(error)}</p>
        ) : bookings.length === 0 ? (
          <div className="empty-state">
            <span className="empty-state-icon" aria-hidden="true"><CalendarDays size={25} /></span>
            <h2>{t('لا توجد حجوزات بعد')}</h2>
            <p>{t('يمكنك إرسال طلب حجز جديد، وستظهر تحديثاته هنا.')}</p>
            <Link className="button button--primary" to="/booking">{t('احجز موعدك')}</Link>
          </div>
        ) : (
          <div className="customer-booking-list">
            {bookings.map((booking) => (
              <article className="owner-booking" key={booking.id}>
                <div className="owner-booking-heading">
                  <div>
                    <h2>{booking.serviceName}</h2>
                    <p>{booking.servicePrice > 0 ? `${booking.servicePrice} ₪` : t('السعر يحدد في المحل')} · {booking.serviceDurationMinutes} {t('دقيقة')}</p>
                  </div>
                  <span className={`booking-status booking-status--${booking.status.toLowerCase()}`}>
                    {t(bookingStatuses[booking.status])}
                  </span>
                </div>
                <dl className="owner-booking-details">
                  <div><dt>{t('طريقة الحجز')}</dt><dd>{t(bookingModes[booking.bookingMode])}</dd></div>
                  <div>
                    <dt>{t(booking.queueDate ? 'تاريخ الدور' : 'الموعد')}</dt>
                    <dd>{booking.queueDate
                      ? new Intl.DateTimeFormat(language === 'ar' ? 'ar-PS' : 'en-US', { timeZone: 'Asia/Hebron', dateStyle: 'medium' })
                        .format(new Date(`${booking.queueDate}T12:00:00+02:00`))
                      : booking.bookingMode === 'CONFIRMATION_REQUEST' && booking.status === 'PENDING' && !booking.startsAt
                        ? t('بانتظار تحديد موعد جديد من المحل')
                        : formatShopDateTime(booking.startsAt, language === 'ar' ? 'ar-PS' : 'en-US')}
                    </dd>
                  </div>
                  {booking.queuePosition && <div><dt>{t('رقم الدور')}</dt><dd>{booking.queuePosition}</dd></div>}
                </dl>
                {(booking.notes || booking.ownerNote) && (
                  <div className="customer-booking-notes">
                    {booking.notes && <p><strong>{t('ملاحظتك للمحل:')}</strong> {booking.notes}</p>}
                    {booking.ownerNote && <p><strong>{t('ملاحظة من المحل:')}</strong> {booking.ownerNote}</p>}
                  </div>
                )}
                {(booking.status === 'PENDING' || booking.status === 'CONFIRMED') && (
                  <div className="customer-booking-actions">
                    <button className="button button--secondary" type="button" disabled={busyBookingId === booking.id}
                      onClick={() => void runBookingAction(booking, 'reschedule')}>
                      <CalendarClock size={16} /> {t('طلب تأجيل')}
                    </button>
                    <button className="button button--danger-outline" type="button" disabled={busyBookingId === booking.id}
                      onClick={() => void runBookingAction(booking, 'cancel')}>
                      <X size={16} /> {t('إلغاء الحجز')}
                    </button>
                  </div>
                )}
              </article>
            ))}
          </div>
        )}
      </section>
      {actionBooking && <div className="customer-action-modal-backdrop" role="presentation"
        onMouseDown={(event) => { if (event.target === event.currentTarget) setActionBooking(null) }}>
        <section className="customer-action-modal" role="dialog" aria-modal="true"
          aria-labelledby="customer-booking-action-title">
          <button className="customer-action-modal-close" type="button" aria-label={t('إغلاق')} onClick={() => setActionBooking(null)}><X size={19} /></button>
          <span className="page-intro-eyebrow">{t('حجوزاتي')}</span>
          <h2 id="customer-booking-action-title">{t(actionBooking.action === 'cancel' ? 'إلغاء الحجز' : 'طلب تأجيل الموعد')}</h2>
          <p>{actionBooking.booking.serviceName}</p>
          {actionBooking.action === 'reschedule' ? <>
            <label>{t('اليوم الذي يناسبك')}<input type="date" min={getShopDate()} value={rescheduleDate}
              onChange={(event) => setRescheduleDate(event.target.value)} required /></label>
            <label>{t('الوقت (اختياري)')}<select value={rescheduleTime} onChange={(event) => setRescheduleTime(event.target.value)}>
              <option value="">{t('اختر اليوم فقط — اقترح أول وقت متاح')}</option>
              {availableSlots.map((slot) => <option key={slot.startsAt} value={slot.startsAt}>{slot.localTime}</option>)}
            </select></label>
            {loadingSlots && <small role="status">{t('جارٍ تحميل الأوقات المتاحة...')}</small>}
            {!loadingSlots && !availableSlots.length && <small>{t('لا توجد أوقات متاحة لهذا اليوم؛ اختر يوماً آخر.')}</small>}
            <label>{t('سبب التأجيل (اختياري)')}<textarea maxLength={500} rows={3} value={rescheduleReason}
              onChange={(event) => setRescheduleReason(event.target.value)} placeholder={t('اكتب سبب التأجيل إن رغبت')} /></label>
          </> : <p>{t('هل تريد إلغاء هذا الحجز؟')}</p>}
          <div className="customer-action-modal-buttons">
            <button className="button button--secondary" type="button" onClick={() => setActionBooking(null)}>{t('رجوع')}</button>
            <button className={actionBooking.action === 'cancel' ? 'button button--danger-outline' : 'button button--primary'}
              type="button" disabled={busyBookingId === actionBooking.booking.id || loadingSlots
                || (actionBooking.action === 'reschedule' && (!rescheduleDate || !availableSlots.length))}
              onClick={() => void submitBookingAction()}>
              {busyBookingId === actionBooking.booking.id ? t('جارٍ الإرسال...') : actionBooking.action === 'cancel' ? t('تأكيد الإلغاء') : t('إرسال طلب التأجيل')}
            </button>
          </div>
        </section>
      </div>}
    </div>
  )
}

export default MyBookingsPage
