import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import PageIntro from '../../../components/ui/PageIntro'
import {
  apiRequest,
  getErrorMessage,
  type Booking,
  type BookingPage,
  type BookingSettings,
  type Service,
} from '../../../lib/api'
import { formatShopDateTime, getShopDate, shopLocalDateTimeToIso } from '../../../lib/shop-time'

const bookingModes = {
  EXACT_APPOINTMENT: 'موعد محدد',
  CONFIRMATION_REQUEST: 'طلب موعد',
  DAY_QUEUE: 'قائمة يومية',
  SAME_DAY_QUEUE: 'دور اليوم',
  LEGACY: 'حجز سابق',
}

const bookingStatuses = {
  PENDING: 'بانتظار التأكيد',
  CONFIRMED: 'مؤكد',
  COMPLETED: 'مكتمل',
  CANCELLED: 'ملغي',
}

const settingOptions: Array<{ key: keyof Pick<
  BookingSettings,
  'exactAppointmentEnabled' | 'confirmationRequestEnabled' | 'dayQueueEnabled' | 'sameDayQueueEnabled'
>; label: string }> = [
  { key: 'exactAppointmentEnabled', label: 'موعد محدد' },
  { key: 'confirmationRequestEnabled', label: 'طلب موعد للتأكيد' },
  { key: 'dayQueueEnabled', label: 'قائمة انتظار ليوم محدد' },
  { key: 'sameDayQueueEnabled', label: 'قائمة انتظار اليوم' },
]

function OwnerPage() {
  const [date, setDate] = useState(getShopDate)
  const [bookings, setBookings] = useState<Booking[]>([])
  const [bookingCount, setBookingCount] = useState(0)
  const [settings, setSettings] = useState<BookingSettings | null>(null)
  const [services, setServices] = useState<Service[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [confirmationId, setConfirmationId] = useState('')
  const [confirmationTime, setConfirmationTime] = useState('')

  const loadData = useCallback(async () => {
    setIsLoading(true)
    setError('')
    try {
      const [bookingPage, currentSettings, allServices] = await Promise.all([
        apiRequest<BookingPage>(`/api/bookings?date=${encodeURIComponent(date)}&page=0&size=100`),
        apiRequest<BookingSettings>('/api/booking-settings'),
        apiRequest<Service[]>('/api/services/manage'),
      ])
      setBookings(bookingPage.content)
      setBookingCount(bookingPage.totalElements)
      setSettings(currentSettings)
      setServices(allServices)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحميل بيانات لوحة الإدارة.'))
    } finally {
      setIsLoading(false)
    }
  }, [date])

  useEffect(() => {
    void Promise.resolve().then(loadData)
  }, [loadData])

  const queueBookings = useMemo(
    () => bookings
      .filter((booking) =>
        (booking.bookingMode === 'DAY_QUEUE' || booking.bookingMode === 'SAME_DAY_QUEUE')
        && (booking.status === 'PENDING' || booking.status === 'CONFIRMED'))
      .sort((left, right) => (left.queuePosition ?? 0) - (right.queuePosition ?? 0)),
    [bookings],
  )

  const runAction = async (label: string, action: () => Promise<unknown>) => {
    setBusy(label)
    setError('')
    setNotice('')
    try {
      await action()
      setNotice('تم حفظ التغيير.')
      setConfirmationId('')
      setConfirmationTime('')
      await loadData()
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تنفيذ الطلب.'))
    } finally {
      setBusy('')
    }
  }

  const handleConfirm = async (event: FormEvent<HTMLFormElement>, booking: Booking) => {
    event.preventDefault()
    let startsAt: string | undefined
    try {
      if (confirmationTime) startsAt = shopLocalDateTimeToIso(confirmationTime)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تحقق من وقت الموعد.'))
      return
    }
    if (booking.bookingMode === 'CONFIRMATION_REQUEST' && !booking.startsAt && !startsAt) {
      setError('اختر وقتاً لتأكيد هذا الطلب.')
      return
    }

    await runAction(`confirm-${booking.id}`, () => apiRequest(`/api/bookings/${booking.id}/confirm`, {
      method: 'PATCH',
      ...(startsAt ? { body: JSON.stringify({ startsAt }) } : {}),
    }))
  }

  const moveQueueBooking = async (bookingId: string, offset: number) => {
    if (bookingCount > 100) {
      setError('تعذر إعادة ترتيب قائمة كبيرة. قلّل النتائج أو أعد المحاولة لاحقاً.')
      return
    }
    const from = queueBookings.findIndex((booking) => booking.id === bookingId)
    const to = from + offset
    if (from < 0 || to < 0 || to >= queueBookings.length) return
    const ordered = [...queueBookings]
    ;[ordered[from], ordered[to]] = [ordered[to], ordered[from]]
    await runAction('reorder-queue', () => apiRequest('/api/bookings/queue/reorder', {
      method: 'PATCH',
      body: JSON.stringify({ date, bookingIds: ordered.map((booking) => booking.id) }),
    }))
  }

  const saveSettings = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!settings) return
    const formData = new FormData(event.currentTarget)
    const updatedSettings = {
      exactAppointmentEnabled: formData.has('exactAppointmentEnabled'),
      confirmationRequestEnabled: formData.has('confirmationRequestEnabled'),
      dayQueueEnabled: formData.has('dayQueueEnabled'),
      sameDayQueueEnabled: formData.has('sameDayQueueEnabled'),
      newBookingsDisabled: !formData.has('newBookingsEnabled'),
    }
    await runAction('save-settings', async () => {
      const saved = await apiRequest<BookingSettings>('/api/booking-settings', {
        method: 'PUT',
        body: JSON.stringify(updatedSettings),
      })
      setSettings(saved)
      return saved
    })
  }

  const createService = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = event.currentTarget
    const formData = new FormData(form)
    const request = {
      name: String(formData.get('name')).trim(),
      description: String(formData.get('description')).trim(),
      price: Number(formData.get('price')),
      durationMinutes: Number(formData.get('durationMinutes')),
    }
    await runAction('create-service', async () => {
      const created = await apiRequest<Service>('/api/services', {
        method: 'POST',
        body: JSON.stringify(request),
      })
      form.reset()
      return created
    })
  }

  const toggleService = async (service: Service) => {
    await runAction(`service-${service.id}`, () =>
      apiRequest(`/api/services/${service.id}/active`, {
        method: 'PATCH',
        body: JSON.stringify({ active: !service.active }),
      }))
  }

  const statusClass = (status: Booking['status']) => `booking-status booking-status--${status.toLowerCase()}`

  return (
    <div className="page-content owner-page">
      <PageIntro
        eyebrow="إدارة المحل"
        title="لوحة المالك"
        description="إدارة الحجوزات والخدمات وطرق استقبال الطلبات."
      />
      <div className="container owner-content">
        {error && <p className="form-message api-state--error" role="alert">{error}</p>}
        {notice && <p className="form-message" role="status">{notice}</p>}
        {isLoading ? (
          <p className="api-state" role="status">جارٍ تحميل لوحة الإدارة...</p>
        ) : (
          <>
            <section className="owner-section" aria-labelledby="bookings-title">
              <div className="owner-section-heading">
                <div>
                  <span className="eyebrow">إدارة المواعيد</span>
                  <h2 id="bookings-title">الحجوزات</h2>
                </div>
                <label className="owner-date-field" htmlFor="booking-date">
                  اليوم
                  <input id="booking-date" type="date" value={date} onChange={(event) => setDate(event.target.value)} />
                </label>
              </div>
              {bookings.length === 0 ? (
                <p className="api-state">لا توجد حجوزات في هذا اليوم.</p>
              ) : (
                <div className="owner-booking-list">
                  {bookings.map((booking) => {
                    const queueIndex = queueBookings.findIndex((queued) => queued.id === booking.id)
                    return (
                      <article className="owner-booking" key={booking.id}>
                        <div className="owner-booking-heading">
                          <div>
                            <h3>{booking.customerName}</h3>
                            <p>{booking.serviceName} · {booking.servicePrice} ₪</p>
                          </div>
                          <span className={statusClass(booking.status)}>{bookingStatuses[booking.status]}</span>
                        </div>
                        <dl className="owner-booking-details">
                          <div><dt>طريقة الحجز</dt><dd>{bookingModes[booking.bookingMode]}</dd></div>
                          <div><dt>التاريخ والوقت</dt><dd>{formatShopDateTime(booking.startsAt)}</dd></div>
                          {booking.queuePosition && <div><dt>الدور</dt><dd>{booking.queuePosition}</dd></div>}
                          {booking.customerPhone && <div><dt>الهاتف</dt><dd dir="ltr">{booking.customerPhone}</dd></div>}
                          {booking.notes && <div><dt>ملاحظة</dt><dd>{booking.notes}</dd></div>}
                        </dl>
                        {booking.status === 'PENDING' && (
                          confirmationId === booking.id ? (
                            <form className="owner-confirm-form" onSubmit={(event) => void handleConfirm(event, booking)}>
                              {booking.bookingMode === 'CONFIRMATION_REQUEST' && !booking.startsAt && (
                                <>
                                  <label htmlFor={`confirm-time-${booking.id}`}>وقت الموعد</label>
                                  <input
                                    id={`confirm-time-${booking.id}`}
                                    type="datetime-local"
                                    value={confirmationTime}
                                    onChange={(event) => setConfirmationTime(event.target.value)}
                                    required
                                  />
                                </>
                              )}
                              <button className="button button--primary" type="submit" disabled={busy !== ''}>
                                تأكيد
                              </button>
                              <button className="owner-secondary-button" type="button" onClick={() => setConfirmationId('')}>
                                رجوع
                              </button>
                            </form>
                          ) : (
                            <div className="owner-booking-actions">
                              <button
                                className="button button--primary"
                                type="button"
                                disabled={busy !== ''}
                                onClick={() => {
                                  if (booking.bookingMode === 'CONFIRMATION_REQUEST' && !booking.startsAt) {
                                    setConfirmationId(booking.id)
                                  } else {
                                    void runAction(`confirm-${booking.id}`, () =>
                                      apiRequest(`/api/bookings/${booking.id}/confirm`, { method: 'PATCH' }))
                                  }
                                }}
                              >تأكيد الحجز</button>
                              <button
                                className="owner-secondary-button"
                                type="button"
                                disabled={busy !== ''}
                                onClick={() => void runAction(`cancel-${booking.id}`, () =>
                                  apiRequest(`/api/bookings/${booking.id}/cancel`, { method: 'PATCH' }))}
                              >إلغاء</button>
                            </div>
                          )
                        )}
                        {booking.status === 'CONFIRMED' && (
                          <div className="owner-booking-actions">
                            <button
                              className="button button--primary"
                              type="button"
                              disabled={busy !== ''}
                              onClick={() => void runAction(`complete-${booking.id}`, () =>
                                apiRequest(`/api/bookings/${booking.id}/complete`, { method: 'PATCH' }))}
                            >إنهاء الحجز</button>
                            <button
                              className="owner-secondary-button"
                              type="button"
                              disabled={busy !== ''}
                              onClick={() => void runAction(`cancel-${booking.id}`, () =>
                                apiRequest(`/api/bookings/${booking.id}/cancel`, { method: 'PATCH' }))}
                            >إلغاء</button>
                          </div>
                        )}
                        {queueIndex >= 0 && (
                          <div className="queue-reorder-actions" aria-label="ترتيب قائمة الانتظار">
                            <button
                              type="button"
                              className="owner-secondary-button"
                              disabled={busy !== '' || queueIndex === 0}
                              onClick={() => void moveQueueBooking(booking.id, -1)}
                            >↑ تقديم الدور</button>
                            <button
                              type="button"
                              className="owner-secondary-button"
                              disabled={busy !== '' || queueIndex === queueBookings.length - 1}
                              onClick={() => void moveQueueBooking(booking.id, 1)}
                            >↓ تأخير الدور</button>
                          </div>
                        )}
                      </article>
                    )
                  })}
                </div>
              )}
              {bookingCount > 100 && (
                <p className="field-help">يعرض النظام أول 100 حجز لهذا اليوم. إعادة ترتيب القائمة متاحة عند ظهور جميع الأدوار.</p>
              )}
            </section>

            {settings && (
              <section className="owner-section" aria-labelledby="settings-title">
                <span className="eyebrow">استقبال الطلبات</span>
                <h2 id="settings-title">إعدادات الحجز</h2>
                <form className="owner-settings-form" onSubmit={(event) => void saveSettings(event)}>
                  <label className="owner-checkbox">
                    <input type="checkbox" name="newBookingsEnabled" defaultChecked={!settings.newBookingsDisabled} />
                    <span>استقبال حجوزات جديدة</span>
                  </label>
                  <div className="owner-setting-grid">
                    {settingOptions.map(({ key, label }) => (
                      <label className="owner-checkbox" key={key}>
                        <input type="checkbox" name={key} defaultChecked={settings[key]} />
                        <span>{label}</span>
                      </label>
                    ))}
                  </div>
                  <button className="button button--primary" type="submit" disabled={busy !== ''}>حفظ الإعدادات</button>
                </form>
              </section>
            )}

            <section className="owner-section" aria-labelledby="services-title">
              <span className="eyebrow">الخدمات والأسعار</span>
              <h2 id="services-title">الخدمات</h2>
              <form className="owner-service-form" onSubmit={(event) => void createService(event)}>
                <label htmlFor="service-name">اسم الخدمة</label>
                <input id="service-name" name="name" minLength={2} maxLength={150} required />
                <label htmlFor="service-description">الوصف</label>
                <textarea id="service-description" name="description" rows={2} maxLength={10000} required />
                <label htmlFor="service-price">السعر (₪)</label>
                <input id="service-price" name="price" type="number" min="0" step="0.01" required />
                <label htmlFor="service-duration">المدة بالدقائق</label>
                <input id="service-duration" name="durationMinutes" type="number" min="1" step="1" required />
                <button className="button button--primary" type="submit" disabled={busy !== ''}>إضافة الخدمة</button>
              </form>
              {services.length === 0 ? (
                <p className="api-state">لم تتم إضافة خدمات بعد.</p>
              ) : (
                <div className="owner-service-list">
                  {services.map((service) => (
                    <article className="owner-service" key={service.id}>
                      <div>
                        <h3>{service.name}</h3>
                        <p>{service.description}</p>
                        <span>{service.price} ₪ · {service.durationMinutes} دقيقة · {service.active ? 'متاحة للحجز' : 'غير متاحة'}</span>
                      </div>
                      <button
                        type="button"
                        className="owner-secondary-button"
                        disabled={busy !== ''}
                        onClick={() => void toggleService(service)}
                      >{service.active ? 'إيقاف الخدمة' : 'تفعيل الخدمة'}</button>
                    </article>
                  ))}
                </div>
              )}
            </section>
          </>
        )}
      </div>
    </div>
  )
}

export default OwnerPage
