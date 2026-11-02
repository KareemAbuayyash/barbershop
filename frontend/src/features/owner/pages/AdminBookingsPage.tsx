import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { CalendarDays, Check, ChevronLeft, ChevronRight, Pencil, Search, X } from 'lucide-react'
import { useSearchParams } from 'react-router-dom'
import { apiRequest, getErrorMessage, type BookingMode, type BookingStatus } from '../../../lib/api'
import { formatShopDateTime, getShopDate, isoToShopLocalDateTime } from '../../../lib/shop-time'
import type { AdminEmployee, AdminPage, AdminBooking } from '../admin-api'

type Preset = 'today' | 'tomorrow' | 'week' | 'month' | 'all' | 'custom'

const presets: Array<{ value: Preset; label: string }> = [
  { value: 'today', label: 'اليوم' },
  { value: 'tomorrow', label: 'غداً' },
  { value: 'week', label: 'هذا الأسبوع' },
  { value: 'month', label: 'هذا الشهر' },
  { value: 'all', label: 'الكل' },
  { value: 'custom', label: 'فترة مخصصة' },
]

const statuses: Array<{ value: BookingStatus | ''; label: string }> = [
  { value: '', label: 'كل الحالات' },
  { value: 'PENDING', label: 'بانتظار التأكيد' },
  { value: 'CONFIRMED', label: 'مؤكد' },
  { value: 'CANCELLED', label: 'ملغي' },
  { value: 'COMPLETED', label: 'مكتمل' },
]

const modes: Record<BookingMode | 'LEGACY', string> = {
  EXACT_APPOINTMENT: 'موعد محدد',
  CONFIRMATION_REQUEST: 'طلب موعد',
  DAY_QUEUE: 'قائمة يومية',
  SAME_DAY_QUEUE: 'دور اليوم',
  LEGACY: 'حجز سابق',
}

const statusNames: Record<BookingStatus, string> = {
  PENDING: 'بانتظار التأكيد',
  CONFIRMED: 'مؤكد',
  CANCELLED: 'ملغي',
  COMPLETED: 'مكتمل',
}

interface BookingAvailability {
  open: boolean
  slots: Array<{ startsAt: string; localTime: string }>
}

function localDate(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function getPresetRange(preset: Preset, today: string): [string, string] | null {
  if (preset === 'all' || preset === 'custom') return null
  const current = new Date(`${today}T12:00:00`)
  if (preset === 'today') return [today, today]
  if (preset === 'tomorrow') {
    current.setDate(current.getDate() + 1)
    const tomorrow = localDate(current)
    return [tomorrow, tomorrow]
  }
  if (preset === 'week') {
    current.setDate(current.getDate() - ((current.getDay() + 6) % 7))
    const start = localDate(current)
    current.setDate(current.getDate() + 6)
    return [start, localDate(current)]
  }
  current.setDate(1)
  const start = localDate(current)
  current.setMonth(current.getMonth() + 1, 0)
  return [start, localDate(current)]
}

function AdminBookingsPage({ queueOnly = false }: { queueOnly?: boolean }) {
  const [searchParams] = useSearchParams()
  const [preset, setPreset] = useState<Preset>('all')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [status, setStatus] = useState<BookingStatus | ''>(() => {
    const initial = searchParams.get('status')
    return statuses.find((item) => item.value === initial)?.value ?? ''
  })
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [result, setResult] = useState<AdminPage<AdminBooking> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [employees, setEmployees] = useState<AdminEmployee[]>([])
  const [busyBookingId, setBusyBookingId] = useState('')
  const [editingBookingId, setEditingBookingId] = useState('')
  const [confirmingBookingId, setConfirmingBookingId] = useState('')
  const [appointmentDate, setAppointmentDate] = useState(getShopDate)
  const [appointmentSlots, setAppointmentSlots] = useState<BookingAvailability | null>(null)
  const [loadingAppointmentSlots, setLoadingAppointmentSlots] = useState(false)
  const [selectedAppointmentSlot, setSelectedAppointmentSlot] = useState('')
  const [refreshKey, setRefreshKey] = useState(0)

  const appliedRange = useMemo(() => getPresetRange(preset, getShopDate()), [preset])

  useEffect(() => {
    void apiRequest<AdminEmployee[]>('/api/admin/employees')
      .then(setEmployees)
      .catch((cause: unknown) => setError(getErrorMessage(cause, 'تعذر تحميل قائمة الموظفين.')))
  }, [])

  useEffect(() => {
    let active = true
    const params = new URLSearchParams({ page: String(page), size: queueOnly ? '100' : '20' })
    const start = preset === 'custom' ? fromDate : appliedRange?.[0]
    const end = preset === 'custom' ? toDate : appliedRange?.[1]
    if (start) params.set('fromDate', start)
    if (end) params.set('toDate', end)
    if (status) params.set('status', status)
    if (query.trim()) params.set('q', query.trim())
    if (queueOnly) params.set('queueOnly', 'true')

    void apiRequest<AdminPage<AdminBooking>>(`/api/bookings?${params}`)
      .then((data) => { if (active) setResult(data) })
      .catch((cause: unknown) => { if (active) setError(getErrorMessage(cause, 'تعذر تحميل الحجوزات.')) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [appliedRange, fromDate, page, preset, query, queueOnly, status, toDate, refreshKey])

  useEffect(() => {
    const activeBookingId = confirmingBookingId || editingBookingId
    const booking = result?.content.find(({ id }) => id === activeBookingId)
    if (!activeBookingId || !booking || !booking.serviceId
      || (booking.bookingMode !== 'EXACT_APPOINTMENT'
        && booking.bookingMode !== 'CONFIRMATION_REQUEST'
        && booking.bookingMode !== 'LEGACY')) return

    let active = true
    const params = new URLSearchParams({
      date: appointmentDate,
      serviceId: booking.serviceId,
    })
    void apiRequest<BookingAvailability>(`/api/bookings/availability/${booking.id}?${params}`)
      .then((data) => { if (active) setAppointmentSlots(data) })
      .catch((cause: unknown) => {
        if (active) setError(getErrorMessage(cause, 'تعذر تحميل المواعيد المتاحة.'))
      })
      .finally(() => { if (active) setLoadingAppointmentSlots(false) })
    return () => { active = false }
  }, [appointmentDate, confirmingBookingId, editingBookingId, refreshKey, result])

  const runBookingAction = async (booking: AdminBooking, action: 'confirm' | 'cancel' | 'complete') => {
    if (action === 'confirm'
      && booking.bookingMode === 'CONFIRMATION_REQUEST'
      && !booking.startsAt) {
      setConfirmingBookingId(booking.id)
      return
    }

    setBusyBookingId(booking.id)
    setError('')
    setNotice('')
    try {
      await apiRequest(`/api/bookings/${booking.id}/${action}`, { method: 'PATCH' })
      setNotice(action === 'confirm' ? 'تم تأكيد الحجز.' : action === 'cancel' ? 'تم إلغاء الحجز.' : 'تم إكمال الحجز.')
      setLoading(true)
      setRefreshKey((value) => value + 1)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحديث حالة الحجز.'))
    } finally { setBusyBookingId('') }
  }

  const confirmWithTime = async (event: FormEvent<HTMLFormElement>, booking: AdminBooking) => {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const startsAt = String(data.get('startsAt') ?? '')
    if (!startsAt) return

    setBusyBookingId(booking.id)
    setError('')
    setNotice('')
    try {
      await apiRequest(`/api/bookings/${booking.id}/confirm`, {
        method: 'PATCH',
        body: JSON.stringify({ startsAt }),
      })
      setConfirmingBookingId('')
      setNotice('تم تحديد الموعد وتأكيد الحجز.')
      setLoading(true)
      setRefreshKey((value) => value + 1)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تأكيد الحجز.'))
    } finally {
      setBusyBookingId('')
    }
  }

  const assignEmployee = async (booking: AdminBooking, employeeId: string) => {
    setBusyBookingId(booking.id)
    setError('')
    setNotice('')
    try {
      await apiRequest(`/api/admin/bookings/${booking.id}/employee`, {
        method: 'PUT',
        body: JSON.stringify({ employeeId: employeeId || null }),
      })
      setNotice('تم تحديث الموظف المسؤول عن الحجز.')
      setLoading(true)
      setRefreshKey((value) => value + 1)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تعيين الموظف.'))
    } finally {
      setBusyBookingId('')
    }
  }

  const saveSchedule = async (event: FormEvent<HTMLFormElement>, booking: AdminBooking) => {
    event.preventDefault()
    const form = event.currentTarget
    const data = new FormData(form)
    setBusyBookingId(booking.id)
    setError('')
    setNotice('')
    try {
      const startInput = String(data.get('startsAt') ?? '')
      await apiRequest(`/api/bookings/${booking.id}/schedule`, {
        method: 'PUT',
        body: JSON.stringify({
          startsAt: startInput || null,
          queueDate: String(data.get('queueDate') ?? '') || null,
          notes: String(data.get('notes') ?? '').trim() || null,
        }),
      })
      setEditingBookingId('')
      setNotice('تم تحديث موعد الحجز.')
      setLoading(true)
      setRefreshKey((value) => value + 1)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحديث موعد الحجز.'))
    } finally { setBusyBookingId('') }
  }

  const moveQueueBooking = async (booking: AdminBooking, direction: -1 | 1) => {
    if (!booking.queueDate) return
    setBusyBookingId(booking.id)
    setError('')
    setNotice('')
    try {
      const all: AdminBooking[] = []
      const params = new URLSearchParams({
        fromDate: booking.queueDate,
        toDate: booking.queueDate,
        queueOnly: 'true',
        size: '100',
      })
      let totalPages = 1
      for (let nextPage = 0; nextPage < totalPages; nextPage += 1) {
        params.set('page', String(nextPage))
        const pageResult = await apiRequest<AdminPage<AdminBooking>>(`/api/bookings?${params}`)
        all.push(...pageResult.content)
        totalPages = pageResult.totalPages
      }
      const active = all
        .filter((item) => item.queuePosition !== null && item.status !== 'CANCELLED' && item.status !== 'COMPLETED')
        .sort((left, right) => (left.queuePosition ?? 0) - (right.queuePosition ?? 0))
      const index = active.findIndex((item) => item.id === booking.id)
      const target = index + direction
      if (index < 0 || target < 0 || target >= active.length) return
      ;[active[index], active[target]] = [active[target], active[index]]
      await apiRequest('/api/bookings/queue/reorder', {
        method: 'PATCH',
        body: JSON.stringify({ date: booking.queueDate, bookingIds: active.map((item) => item.id) }),
      })
      setNotice('تم تحديث ترتيب قائمة الانتظار.')
      setLoading(true)
      setRefreshKey((value) => value + 1)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر إعادة ترتيب قائمة الانتظار.'))
    } finally { setBusyBookingId('') }
  }

  const choosePreset = (next: Preset) => {
    setLoading(true)
    setError('')
    setPreset(next)
    setPage(0)
    if (next === 'custom' && !fromDate && !toDate) {
      const today = getShopDate()
      setFromDate(today)
      setToDate(today)
    }
  }

  const changeDate = (setter: (value: string) => void, value: string) => {
    setLoading(true)
    setError('')
    setter(value)
    setPage(0)
  }

  return (
    <div className="admin-page">
      <section className="admin-panel">
        <div className="admin-panel-heading">
          <div><span className="admin-eyebrow">{queueOnly ? 'تنظيم الأدوار' : 'سجل المحل'}</span>
            <h2>{queueOnly ? 'قائمة الانتظار' : 'جميع الحجوزات'}</h2></div>
          <span className="admin-result-count">{result?.totalElements.toLocaleString('ar') ?? '—'} حجز</span>
        </div>
        <div className="admin-filter-pills" role="group" aria-label="الفترة الزمنية">
          {presets.map((item) => (
            <button
              type="button"
              key={item.value}
              className={preset === item.value ? 'admin-filter-pill admin-filter-pill--active' : 'admin-filter-pill'}
              onClick={() => choosePreset(item.value)}
            >{item.label}</button>
          ))}
        </div>
        {preset === 'custom' && (
          <div className="admin-date-range">
            <label>من التاريخ<input type="date" value={fromDate} max={toDate || undefined}
              onClick={(event) => event.currentTarget.showPicker?.()}
              onChange={(event) => changeDate(setFromDate, event.target.value)} /></label>
            <label>إلى التاريخ<input type="date" value={toDate} min={fromDate || undefined}
              onClick={(event) => event.currentTarget.showPicker?.()}
              onChange={(event) => changeDate(setToDate, event.target.value)} /></label>
            <span className="admin-filter-note"><CalendarDays size={15} /> التواريخ حسب توقيت المحل</span>
          </div>
        )}
        <div className="admin-filter-row">
          <label className="admin-search">
            <Search size={17} aria-hidden="true" />
            <input value={query} maxLength={150} placeholder="ابحث باسم العميل أو رقم الهاتف"
            onChange={(event) => { setLoading(true); setError(''); setQuery(event.target.value); setPage(0) }} />
          </label>
          <label className="admin-select-field" htmlFor="booking-status">حالة الحجز
            <select id="booking-status" value={status} onChange={(event) => {
              setLoading(true)
              setError('')
              setStatus(event.target.value as BookingStatus | '')
              setPage(0)
            }}>
              {statuses.map((item) => <option value={item.value} key={item.value}>{item.label}</option>)}
            </select>
          </label>
        </div>
        {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
        {notice && <p className="admin-message" role="status">{notice}</p>}
        {loading ? (
          <p className="admin-state" role="status">جارٍ تحميل الحجوزات...</p>
        ) : !result?.content.length ? (
          <div className="admin-empty"><CalendarDays size={24} /><h3>لا توجد حجوزات مطابقة</h3>
            <p>جرّب تغيير الفترة أو إزالة بعض عوامل التصفية.</p></div>
        ) : (
          <>
            <div className="admin-booking-table">
              <div className="admin-table-head">
                <span>العميل</span><span>الخدمة</span><span>التاريخ</span><span>الحالة</span><span>القيمة</span><span>الإجراءات</span>
              </div>
              {result.content.map((booking) => (
                <div className="admin-booking-entry" key={booking.id}>
                <article className="admin-booking-row">
                  <div><strong>{booking.customerName || 'عميل'}</strong>
                    <small dir="ltr">{booking.customerPhone || '—'}</small></div>
                  <div><strong>{booking.serviceName}</strong><small>{modes[booking.bookingMode]}</small></div>
                  <div><strong>{booking.queueDate
                    ? new Intl.DateTimeFormat('ar', { dateStyle: 'medium', timeZone: 'Asia/Hebron' })
                      .format(new Date(`${booking.queueDate}T12:00:00+02:00`))
                    : formatShopDateTime(booking.startsAt)}</strong>
                    {booking.queuePosition && <small>الدور {booking.queuePosition}</small>}</div>
                  <span className={`booking-status booking-status--${booking.status.toLowerCase()}`}>
                    {statusNames[booking.status]}</span>
                  <strong className="admin-booking-price">{booking.servicePrice.toLocaleString('ar')} ₪</strong>
                  <div className="admin-booking-actions">
                    {booking.status === 'PENDING' && <button type="button" className="admin-icon-button"
                      aria-label={booking.bookingMode === 'CONFIRMATION_REQUEST' && !booking.startsAt
                        ? 'تحديد موعد وتأكيد الحجز' : 'تأكيد الحجز'}
                      title={booking.bookingMode === 'CONFIRMATION_REQUEST' && !booking.startsAt
                        ? 'تحديد موعد وتأكيد الحجز' : 'تأكيد الحجز'}
                      disabled={busyBookingId === booking.id}
                      onClick={() => {
                        if (booking.bookingMode === 'CONFIRMATION_REQUEST' && !booking.startsAt) {
                          setAppointmentDate(getShopDate())
                          setSelectedAppointmentSlot('')
                          setAppointmentSlots(null)
                          setLoadingAppointmentSlots(true)
                        }
                        void runBookingAction(booking, 'confirm')
                      }}><Check size={16} /></button>}
                    {booking.status === 'CONFIRMED' && <button type="button" className="admin-icon-button"
                      aria-label="إكمال الحجز" title="إكمال الحجز" disabled={busyBookingId === booking.id}
                      onClick={() => void runBookingAction(booking, 'complete')}><Check size={16} /></button>}
                    {(booking.status === 'PENDING' || booking.status === 'CONFIRMED') && <button type="button"
                      className="admin-icon-button admin-icon-button--danger" aria-label="إلغاء الحجز"
                      title="إلغاء الحجز" disabled={busyBookingId === booking.id}
                      onClick={() => void runBookingAction(booking, 'cancel')}><X size={16} /></button>}
                    {queueOnly && booking.queuePosition !== null && booking.status !== 'CANCELLED'
                      && booking.status !== 'COMPLETED' && (
                        <>
                          <button type="button" className="admin-icon-button" aria-label="رفع ترتيب الدور"
                            title="رفع الدور" disabled={busyBookingId === booking.id || booking.queuePosition === 1}
                            onClick={() => void moveQueueBooking(booking, -1)}>↑</button>
                          <button type="button" className="admin-icon-button" aria-label="خفض ترتيب الدور"
                            title="خفض الدور" disabled={busyBookingId === booking.id}
                            onClick={() => void moveQueueBooking(booking, 1)}>↓</button>
                        </>
                      )}
                    {booking.status !== 'CANCELLED' && booking.status !== 'COMPLETED' && (
                      <select aria-label={`الموظف المسؤول عن ${booking.customerName || 'الحجز'}`}
                        value={booking.employeeId ?? ''} disabled={busyBookingId === booking.id}
                        onChange={(event) => void assignEmployee(booking, event.target.value)}>
                        <option value="">بدون موظف</option>
                        {employees.filter((employee) => employee.active).map((employee) =>
                          <option key={employee.id} value={employee.id}>{employee.name}</option>)}
                      </select>
                    )}
                    {(booking.status === 'PENDING' || booking.status === 'CONFIRMED')
                      && (booking.bookingMode !== 'LEGACY' || booking.startsAt) && (
                        <button type="button" className="admin-icon-button" aria-label="تعديل الموعد"
                          title="تعديل الموعد" disabled={busyBookingId === booking.id}
                          onClick={() => {
                            if (editingBookingId === booking.id) {
                              setEditingBookingId('')
                              return
                            }
                            setAppointmentDate(booking.startsAt
                              ? isoToShopLocalDateTime(booking.startsAt).slice(0, 10)
                              : getShopDate())
                            setSelectedAppointmentSlot(booking.startsAt ?? '')
                            setAppointmentSlots(null)
                            setLoadingAppointmentSlots(
                              booking.bookingMode === 'EXACT_APPOINTMENT'
                                || booking.bookingMode === 'CONFIRMATION_REQUEST'
                                || booking.bookingMode === 'LEGACY',
                            )
                            setConfirmingBookingId('')
                            setEditingBookingId(booking.id)
                          }}>
                          <Pencil size={15} />
                        </button>
                      )}
                  </div>
                </article>
                {editingBookingId === booking.id && (
                  <form className="admin-booking-edit" onSubmit={(event) => void saveSchedule(event, booking)}>
                    {(booking.bookingMode === 'EXACT_APPOINTMENT' || booking.bookingMode === 'CONFIRMATION_REQUEST'
                      || booking.bookingMode === 'LEGACY') && (
                        <>
                          <label>تاريخ الموعد
                            <input type="date" min={getShopDate()} value={appointmentDate}
                              onChange={(event) => {
                                setAppointmentDate(event.target.value)
                                setAppointmentSlots(null)
                                setSelectedAppointmentSlot('')
                                setLoadingAppointmentSlots(true)
                              }}
                              onClick={(event) => event.currentTarget.showPicker?.()} required />
                          </label>
                          <label>{booking.bookingMode === 'CONFIRMATION_REQUEST' ? 'الوقت المقترح' : 'الموعد'}
                            <select name="startsAt" value={selectedAppointmentSlot}
                              onChange={(event) => setSelectedAppointmentSlot(event.target.value)}
                              required={booking.bookingMode !== 'CONFIRMATION_REQUEST'}>
                              {booking.bookingMode === 'CONFIRMATION_REQUEST' && <option value="">بدون وقت مقترح</option>}
                              {appointmentSlots?.slots.map((slot) => (
                                <option key={slot.startsAt} value={slot.startsAt}>{slot.localTime}</option>
                              ))}
                            </select>
                          </label>
                          {loadingAppointmentSlots && <p className="admin-form-note">جارٍ تحميل الأوقات المتاحة...</p>}
                          {!loadingAppointmentSlots && appointmentSlots && !appointmentSlots.open
                            && <p className="admin-form-note">المحل مغلق في هذا اليوم.</p>}
                          {!loadingAppointmentSlots && appointmentSlots?.open
                            && appointmentSlots.slots.length === 0
                            && <p className="admin-form-note">لا توجد أوقات متاحة في هذا اليوم.</p>}
                        </>
                      )}
                    {booking.bookingMode === 'DAY_QUEUE' && (
                      <label>تاريخ الدور
                        <input name="queueDate" type="date" min={getShopDate()} defaultValue={booking.queueDate ?? ''}
                          onClick={(event) => event.currentTarget.showPicker?.()} required />
                      </label>
                    )}
                    {booking.bookingMode === 'SAME_DAY_QUEUE' && (
                      <p className="admin-form-note">هذا حجز لقائمة اليوم؛ يمكن تعديل الملاحظات فقط.</p>
                    )}
                    <label className="admin-booking-notes">ملاحظات
                      <input name="notes" maxLength={1000} defaultValue={booking.notes ?? ''} />
                    </label>
                    <div className="admin-inline-actions">
                      <button className="admin-primary-button" disabled={busyBookingId === booking.id}>حفظ التعديل</button>
                      <button className="admin-secondary-button" type="button"
                        onClick={() => setEditingBookingId('')}>إلغاء</button>
                    </div>
                  </form>
                )}
                {confirmingBookingId === booking.id && (
                  <form className="admin-booking-confirm" onSubmit={(event) => void confirmWithTime(event, booking)}>
                    <label>تاريخ الموعد
                      <input type="date" min={getShopDate()} value={appointmentDate}
                        onChange={(event) => {
                          setAppointmentDate(event.target.value)
                          setAppointmentSlots(null)
                          setSelectedAppointmentSlot('')
                          setLoadingAppointmentSlots(true)
                        }}
                        onClick={(event) => event.currentTarget.showPicker?.()} required />
                    </label>
                    <label>اختر وقتاً متاحاً
                      <select name="startsAt" value={selectedAppointmentSlot}
                        onChange={(event) => setSelectedAppointmentSlot(event.target.value)} required>
                        <option value="" disabled>اختر الوقت</option>
                        {appointmentSlots?.slots.map((slot) => (
                          <option key={slot.startsAt} value={slot.startsAt}>{slot.localTime}</option>
                        ))}
                      </select>
                    </label>
                    <button className="admin-primary-button" disabled={busyBookingId === booking.id}>
                      {busyBookingId === booking.id ? 'جارٍ التأكيد...' : 'تأكيد الموعد'}
                    </button>
                    <button className="admin-secondary-button" type="button"
                      onClick={() => setConfirmingBookingId('')}>إلغاء</button>
                  </form>
                )}
                </div>
              ))}
            </div>
            <div className="admin-pagination">
              <span>صفحة {(result.page + 1).toLocaleString('ar')} من {Math.max(result.totalPages, 1).toLocaleString('ar')}</span>
              <div><button type="button" aria-label="الصفحة السابقة" disabled={page === 0}
                onClick={() => { setLoading(true); setPage((current) => Math.max(0, current - 1)) }}><ChevronRight size={18} /></button>
                <button type="button" aria-label="الصفحة التالية" disabled={page + 1 >= result.totalPages}
                  onClick={() => { setLoading(true); setPage((current) => current + 1) }}><ChevronLeft size={18} /></button></div>
            </div>
          </>
        )}
      </section>
    </div>
  )
}

export default AdminBookingsPage
