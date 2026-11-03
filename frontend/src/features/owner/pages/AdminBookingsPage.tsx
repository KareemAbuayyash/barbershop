import { Fragment, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { createPortal } from 'react-dom'
import { CalendarDays, Check, ChevronLeft, ChevronRight, Pencil, Plus, Search, Trash2, X } from 'lucide-react'
import { useSearchParams } from 'react-router-dom'
import { apiRequest, getErrorMessage, type BookingMode, type BookingStatus } from '../../../lib/api'
import { formatShopDateTime, getShopDate, isoToShopLocalDateTime } from '../../../lib/shop-time'
import type { AdminPage, AdminBooking, AdminProduct } from '../admin-api'

type Preset = 'today' | 'tomorrow' | 'week' | 'month' | 'all' | 'custom'
type BookingStatusFilter = BookingStatus | '' | 'ACTIVE'

const presets: Array<{ value: Preset; label: string }> = [
  { value: 'today', label: 'اليوم' },
  { value: 'tomorrow', label: 'غداً' },
  { value: 'week', label: 'هذا الأسبوع' },
  { value: 'month', label: 'هذا الشهر' },
  { value: 'all', label: 'الكل' },
  { value: 'custom', label: 'فترة مخصصة' },
]

const statuses: Array<{ value: BookingStatusFilter; label: string }> = [
  { value: 'ACTIVE', label: 'بانتظار التأكيد والمؤكد' },
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

interface CheckoutLine {
  key: number
  productId: string
  quantity: string
  unitPrice: string
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

function checkoutTotal(servicePrice: number, lines: CheckoutLine[]) {
  const retailTotal = lines.reduce((sum, line) => {
    const lineTotal = (Number(line.quantity) || 0) * (Number(line.unitPrice) || 0)
    return sum + Math.round((lineTotal + Number.EPSILON) * 100) / 100
  }, 0)
  return Math.round((servicePrice + retailTotal + Number.EPSILON) * 100) / 100
}

function AdminBookingsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const queueOnly = searchParams.get('view') === 'queue'
  const [preset, setPreset] = useState<Preset>('all')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [status, setStatus] = useState<BookingStatusFilter>(() => {
    const initial = searchParams.get('status')
    return statuses.find((item) => item.value === initial)?.value ?? 'ACTIVE'
  })
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [result, setResult] = useState<AdminPage<AdminBooking> | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [products, setProducts] = useState<AdminProduct[]>([])
  const [productsLoaded, setProductsLoaded] = useState(false)
  const [busyBookingId, setBusyBookingId] = useState('')
  const [editingBookingId, setEditingBookingId] = useState('')
  const [confirmingBookingId, setConfirmingBookingId] = useState('')
  const [completingBookingId, setCompletingBookingId] = useState('')
  const [checkoutLines, setCheckoutLines] = useState<CheckoutLine[]>([])
  const [checkoutAmount, setCheckoutAmount] = useState('')
  const [checkoutMethod, setCheckoutMethod] = useState('CASH')
  const [checkoutNotes, setCheckoutNotes] = useState('')
  const [appointmentDate, setAppointmentDate] = useState(getShopDate)
  const [appointmentSlots, setAppointmentSlots] = useState<BookingAvailability | null>(null)
  const [loadingAppointmentSlots, setLoadingAppointmentSlots] = useState(false)
  const [selectedAppointmentSlot, setSelectedAppointmentSlot] = useState('')
  const [refreshKey, setRefreshKey] = useState(0)
  const [queueReordering, setQueueReordering] = useState(false)
  const queueReorderInFlight = useRef(false)

  const appliedRange = useMemo(() => getPresetRange(preset, getShopDate()), [preset])
  const displayedBookings = useMemo(() => {
    if (!result) return []
    if (!queueOnly) return result.content
    return [...result.content].sort((left, right) => {
      const dateOrder = (left.queueDate ?? '').localeCompare(right.queueDate ?? '')
      if (dateOrder) return dateOrder
      const positionOrder = (left.queuePosition ?? Number.MAX_SAFE_INTEGER)
        - (right.queuePosition ?? Number.MAX_SAFE_INTEGER)
      if (positionOrder) return positionOrder
      return left.createdAt.localeCompare(right.createdAt)
    })
  }, [queueOnly, result])

  useEffect(() => {
    void apiRequest<AdminProduct[]>('/api/admin/inventory/products')
      .then(setProducts)
      .catch((cause: unknown) => setError(getErrorMessage(cause, 'تعذر تحميل منتجات المخزون.')))
      .finally(() => setProductsLoaded(true))
  }, [])

  useEffect(() => {
    let active = true
    const params = new URLSearchParams({ page: String(page), size: queueOnly ? '100' : '20' })
    const start = preset === 'custom' ? fromDate : appliedRange?.[0]
    const end = preset === 'custom' ? toDate : appliedRange?.[1]
    if (start) params.set('fromDate', start)
    if (end) params.set('toDate', end)
    if (status === 'ACTIVE') params.set('activeOnly', 'true')
    else if (status) params.set('status', status)
    if (query.trim()) params.set('q', query.trim())
    if (queueOnly) params.set('queueOnly', 'true')

    void apiRequest<AdminPage<AdminBooking>>(`/api/bookings?${params}`)
      .then((data) => { if (active) setResult(data) })
      .catch((cause: unknown) => { if (active) setError(getErrorMessage(cause, 'تعذر تحميل الحجوزات.')) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [appliedRange, fromDate, page, preset, query, queueOnly, status, toDate, refreshKey])

  useEffect(() => {
    let refreshTimer = 0
    const refreshBookings = () => {
      setRefreshKey((value) => value + 1)
    }
    const onServiceWorkerMessage = (event: MessageEvent) => {
      if (event.data?.type === 'barbershop:notification-received') {
        window.clearTimeout(refreshTimer)
        refreshTimer = window.setTimeout(refreshBookings, 250)
      }
    }
    window.addEventListener('focus', refreshBookings)
    navigator.serviceWorker?.addEventListener('message', onServiceWorkerMessage)
    return () => {
      window.clearTimeout(refreshTimer)
      window.removeEventListener('focus', refreshBookings)
      navigator.serviceWorker?.removeEventListener('message', onServiceWorkerMessage)
    }
  }, [])

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
      .then((data) => {
        if (active) {
          setAppointmentSlots(data)
          setSelectedAppointmentSlot((current) =>
            data.slots.some((slot) => slot.startsAt === current) ? current : '')
        }
      })
      .catch((cause: unknown) => {
        if (active) setError(getErrorMessage(cause, 'تعذر تحميل المواعيد المتاحة.'))
      })
      .finally(() => { if (active) setLoadingAppointmentSlots(false) })
    return () => { active = false }
  }, [appointmentDate, confirmingBookingId, editingBookingId, refreshKey, result])

  const runBookingAction = async (booking: AdminBooking, action: 'confirm' | 'cancel') => {
    if (action === 'confirm' && booking.bookingMode === 'CONFIRMATION_REQUEST') {
      setError('')
      setNotice('')
      setAppointmentDate(booking.preferredDate
        ?? (booking.startsAt ? isoToShopLocalDateTime(booking.startsAt).slice(0, 10) : getShopDate()))
      setSelectedAppointmentSlot(booking.startsAt ?? '')
      setAppointmentSlots(null)
      setLoadingAppointmentSlots(true)
      setConfirmingBookingId(booking.id)
      setEditingBookingId('')
      return
    }

    setBusyBookingId(booking.id)
    setError('')
    setNotice('')
    try {
      const updated = await apiRequest<AdminBooking>(`/api/bookings/${booking.id}/${action}`, { method: 'PATCH' })
      setResult((current) => current && ({
        ...current,
        content: current.content.map((item) => item.id === updated.id ? updated : item),
      }))
      setNotice(action === 'confirm' ? 'تم تأكيد الحجز.' : 'تم إلغاء الحجز.')
      setLoading(true)
      setRefreshKey((value) => value + 1)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحديث حالة الحجز.'))
    } finally { setBusyBookingId('') }
  }

  const checkoutBooking = async (event: FormEvent<HTMLFormElement>, booking: AdminBooking) => {
    event.preventDefault()
    setBusyBookingId(booking.id)
    setError('')
    setNotice('')
    try {
      await apiRequest(`/api/admin/bookings/${booking.id}/checkout`, {
        method: 'POST',
        body: JSON.stringify({
          amount: Number(checkoutAmount || checkoutTotal(booking.servicePrice, checkoutLines)),
          method: checkoutMethod,
          notes: checkoutNotes.trim() || null,
          products: checkoutLines.filter((line) => line.productId).map((line) => ({
            productId: line.productId,
            quantity: Number(line.quantity),
            unitPrice: Number(line.unitPrice),
          })),
        }),
      })
      setCompletingBookingId('')
      setCheckoutLines([])
      setCheckoutNotes('')
      setNotice('تم إنهاء الحجز وتسجيل التحصيل والمنتجات المباعة.')
      setLoading(true)
      setRefreshKey((value) => value + 1)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر إنهاء الحجز وتسجيل التحصيل.'))
    } finally {
      setBusyBookingId('')
    }
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
      const updated = await apiRequest<AdminBooking>(`/api/bookings/${booking.id}/confirm`, {
        method: 'PATCH',
        body: JSON.stringify({ startsAt }),
      })
      setResult((current) => current && ({
        ...current,
        content: current.content.map((item) => item.id === updated.id ? updated : item),
      }))
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
          preferredDate: booking.bookingMode === 'CONFIRMATION_REQUEST' ? appointmentDate : null,
          queueDate: String(data.get('queueDate') ?? '') || null,
          ownerNote: String(data.get('ownerNote') ?? '').trim(),
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
    if (!booking.queueDate || queueReorderInFlight.current) return
    queueReorderInFlight.current = true
    setQueueReordering(true)
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
      // Keep the current list visible while the refreshed order loads.
      setRefreshKey((value) => value + 1)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر إعادة ترتيب قائمة الانتظار.'))
    } finally {
      queueReorderInFlight.current = false
      setQueueReordering(false)
      setBusyBookingId('')
    }
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

  const chooseBookingView = (nextQueueOnly: boolean) => {
    setPage(0)
    setLoading(true)
    setError('')
    setStatus('ACTIVE')
    setSearchParams((current) => {
      const next = new URLSearchParams(current)
      if (nextQueueOnly) next.set('view', 'queue')
      else next.delete('view')
      return next
    })
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
            <h2>{queueOnly ? 'قائمة يومية' : 'جميع الحجوزات'}</h2></div>
          <span className="admin-result-count">{result?.totalElements.toLocaleString('ar') ?? '—'} حجز</span>
        </div>
        <div className="admin-filter-pills" role="group" aria-label="عرض الحجوزات">
          <button type="button" className={!queueOnly ? 'admin-filter-pill admin-filter-pill--active' : 'admin-filter-pill'}
            aria-pressed={!queueOnly} onClick={() => chooseBookingView(false)}>الحجوزات</button>
          <button type="button" className={queueOnly ? 'admin-filter-pill admin-filter-pill--active' : 'admin-filter-pill'}
            aria-pressed={queueOnly} onClick={() => chooseBookingView(true)}>قائمة يومية</button>
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
              setStatus(event.target.value as BookingStatusFilter)
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
                {displayedBookings.map((booking, index) => (
                <Fragment key={booking.id}>
                  {!queueOnly && (index === 0 || displayedBookings[index - 1]?.status !== booking.status) && (
                    <h3 className="admin-booking-group-heading">{statusNames[booking.status]}</h3>
                  )}
                  <div className="admin-booking-entry">
                <article className="admin-booking-row">
                  <div data-label="العميل"><strong>{booking.customerName || 'عميل'}</strong>
                    <small dir="ltr">{booking.customerPhone || '—'}</small></div>
                  <div data-label="الخدمة"><strong>{booking.serviceName}</strong><small>{modes[booking.bookingMode]}</small></div>
                  <div data-label="التاريخ"><strong>{booking.queueDate
                    ? new Intl.DateTimeFormat('ar', { dateStyle: 'medium', timeZone: 'Asia/Hebron' })
                      .format(new Date(`${booking.queueDate}T12:00:00+02:00`))
                  : booking.startsAt
                    ? formatShopDateTime(booking.startsAt)
                    : booking.preferredDate
                      ? new Intl.DateTimeFormat('ar', { dateStyle: 'medium', timeZone: 'Asia/Hebron' })
                        .format(new Date(`${booking.preferredDate}T12:00:00+02:00`))
                      : 'لم يُحدد بعد'}</strong>
                  {booking.bookingMode === 'CONFIRMATION_REQUEST' && booking.status === 'PENDING'
                    && (booking.preferredDate || booking.startsAt)
                    && <small>{booking.startsAt ? 'الوقت الذي فضّله العميل' : 'التاريخ الذي فضّله العميل'}</small>}
                    {booking.queuePosition && <small>الدور {booking.queuePosition}</small>}</div>
                  <span data-label="الحالة" className={`booking-status booking-status--${booking.status.toLowerCase()}`}>
                    {statusNames[booking.status]}</span>
                  <strong data-label="القيمة" className="admin-booking-price">{booking.servicePrice.toLocaleString('ar')} ₪</strong>
                  <div data-label="الإجراءات" className="admin-booking-actions">
                    {booking.status === 'PENDING' && <button type="button" className="admin-icon-button"
                      aria-label={booking.bookingMode === 'CONFIRMATION_REQUEST'
                        ? 'تحديد موعد وتأكيد الحجز' : 'تأكيد الحجز'}
                      title={booking.bookingMode === 'CONFIRMATION_REQUEST'
                        ? 'تحديد موعد وتأكيد الحجز' : 'تأكيد الحجز'}
                      disabled={busyBookingId === booking.id}
                      onClick={() => void runBookingAction(booking, 'confirm')}><Check size={16} /></button>}
                    {booking.status === 'CONFIRMED' && <button type="button" className="admin-icon-button"
                      aria-label="إنهاء الحجز وتسجيل الدفع" title="إنهاء الحجز وتسجيل الدفع"
                      disabled={busyBookingId === booking.id}
                      onClick={() => {
                        setCompletingBookingId(booking.id)
                        setCheckoutLines([])
                        setCheckoutAmount('')
                        setCheckoutMethod('CASH')
                        setCheckoutNotes('')
                        setError('')
                        setNotice('')
                      }}><Check size={16} /></button>}
                    {(booking.status === 'PENDING' || booking.status === 'CONFIRMED') && <button type="button"
                      className="admin-icon-button admin-icon-button--danger" aria-label="إلغاء الحجز"
                      title="إلغاء الحجز" disabled={busyBookingId === booking.id}
                      onClick={() => void runBookingAction(booking, 'cancel')}><X size={16} /></button>}
                    {queueOnly && booking.queuePosition !== null && booking.status !== 'CANCELLED'
                      && booking.status !== 'COMPLETED' && (
                        <>
                          <button type="button" className="admin-icon-button" aria-label="رفع ترتيب الدور"
                            title="رفع الدور"
                            disabled={queueReordering || busyBookingId === booking.id || booking.queuePosition === 1}
                            onClick={() => void moveQueueBooking(booking, -1)}>↑</button>
                          <button type="button" className="admin-icon-button" aria-label="خفض ترتيب الدور"
                            title="خفض الدور"
                            disabled={queueReordering || busyBookingId === booking.id}
                            onClick={() => void moveQueueBooking(booking, 1)}>↓</button>
                        </>
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
                              : booking.preferredDate ?? getShopDate())
                            setSelectedAppointmentSlot(booking.startsAt ?? '')
                            setAppointmentSlots(null)
                            setError('')
                            setNotice('')
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
                </div>
                {editingBookingId === booking.id && createPortal(
                  <div className="admin-booking-modal-backdrop" role="presentation"
                    onMouseDown={(event) => {
                      if (event.target === event.currentTarget) setEditingBookingId('')
                    }}>
                    <section className="admin-booking-modal" role="dialog" aria-modal="true"
                      aria-labelledby="booking-edit-title" dir="rtl">
                    <div className="admin-booking-modal-heading">
                      <div><span className="admin-eyebrow">إدارة الحجز</span>
                        <h2 id="booking-edit-title">تعديل تفاصيل الموعد</h2></div>
                      <button type="button" className="admin-icon-button" aria-label="إغلاق"
                        onClick={() => setEditingBookingId('')}><X size={17} /></button>
                    </div>
                  <form className="admin-booking-edit" onSubmit={(event) => void saveSchedule(event, booking)}>
                    {(booking.bookingMode === 'EXACT_APPOINTMENT' || booking.bookingMode === 'CONFIRMATION_REQUEST'
                      || booking.bookingMode === 'LEGACY') && (
                        <>
                          <label>{booking.bookingMode === 'CONFIRMATION_REQUEST' && booking.status === 'PENDING'
                            ? 'التاريخ المفضّل' : 'تاريخ الموعد'}
                            <input type="date" min={getShopDate()} value={appointmentDate}
                              onChange={(event) => {
                                setAppointmentDate(event.target.value)
                                setAppointmentSlots(null)
                                setSelectedAppointmentSlot('')
                                setLoadingAppointmentSlots(true)
                              }}
                              onClick={(event) => event.currentTarget.showPicker?.()} required />
                          </label>
                          <label>{booking.bookingMode === 'CONFIRMATION_REQUEST' && booking.status === 'PENDING'
                            ? 'الوقت المفضّل (اختياري)' : 'الموعد'}
                            <select name="startsAt" value={selectedAppointmentSlot}
                              onChange={(event) => setSelectedAppointmentSlot(event.target.value)}
                              required={booking.bookingMode !== 'CONFIRMATION_REQUEST' || booking.status === 'CONFIRMED'}>
                              {booking.bookingMode === 'CONFIRMATION_REQUEST' && booking.status === 'PENDING'
                                && <option value="">بدون وقت مقترح</option>}
                              {appointmentSlots?.slots.map((slot) => (
                                <option key={slot.startsAt} value={slot.startsAt}>{slot.localTime}</option>
                              ))}
                            </select>
                          </label>
                          {loadingAppointmentSlots && <p className="admin-form-note">جارٍ تحميل الأوقات المتاحة...</p>}
                          {!loadingAppointmentSlots && appointmentSlots && !appointmentSlots.open
                            && <p className="admin-form-note">المحل مغلق في هذا التاريخ؛ اختر تاريخاً آخر أو اترك الوقت المفضّل فارغاً.</p>}
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
                    {booking.notes && <p className="admin-form-note admin-booking-customer-note">
                      <strong>ملاحظة الزبون:</strong> {booking.notes}
                    </p>}
                    <label className="admin-booking-notes">ملاحظة من المحل (تظهر للزبون)
                      <input name="ownerNote" maxLength={1000} defaultValue={booking.ownerNote ?? ''} />
                    </label>
                    <div className="admin-inline-actions">
                      <button className="admin-primary-button" disabled={busyBookingId === booking.id}>حفظ التعديل</button>
                      <button className="admin-secondary-button" type="button"
                        onClick={() => setEditingBookingId('')}>إلغاء</button>
                    </div>
                  </form>
                    </section>
                  </div>,
                  document.body,
                )}
                {confirmingBookingId === booking.id && createPortal(
                  <div className="admin-booking-modal-backdrop" role="presentation"
                    onMouseDown={(event) => {
                      if (event.target === event.currentTarget) setConfirmingBookingId('')
                    }}>
                    <section className="admin-booking-modal" role="dialog" aria-modal="true"
                      aria-labelledby="booking-confirm-title" dir="rtl">
                    <div className="admin-booking-modal-heading">
                      <div><span className="admin-eyebrow">{booking.customerName || 'العميل'} · {booking.serviceName}</span>
                        <h2 id="booking-confirm-title">اختيار موعد وتأكيد الحجز</h2></div>
                      <button type="button" className="admin-icon-button" aria-label="إغلاق"
                        onClick={() => setConfirmingBookingId('')}><X size={17} /></button>
                    </div>
                  <form className="admin-booking-confirm" onSubmit={(event) => void confirmWithTime(event, booking)}>
                    {booking.preferredDate && <p className="admin-form-note">
                      التاريخ الذي فضّله العميل: {new Intl.DateTimeFormat('ar', { dateStyle: 'medium' })
                        .format(new Date(`${booking.preferredDate}T12:00:00`))}
                    </p>}
                    <label>تاريخ الموعد المعتمد
                      <input type="date" min={getShopDate()} value={appointmentDate}
                        onChange={(event) => {
                          setAppointmentDate(event.target.value)
                          setAppointmentSlots(null)
                          setSelectedAppointmentSlot('')
                          setLoadingAppointmentSlots(true)
                        }}
                        onClick={(event) => event.currentTarget.showPicker?.()} required />
                    </label>
                    <label>وقت الموعد المعتمد
                      <select name="startsAt" value={selectedAppointmentSlot}
                        onChange={(event) => setSelectedAppointmentSlot(event.target.value)} required>
                        <option value="" disabled>اختر الوقت</option>
                        {appointmentSlots?.slots.map((slot) => (
                          <option key={slot.startsAt} value={slot.startsAt}>{slot.localTime}</option>
                        ))}
                      </select>
                    </label>
                    {!loadingAppointmentSlots && appointmentSlots && !appointmentSlots.open
                      && <p className="admin-form-note">المحل مغلق في هذا التاريخ. اختر يوماً مفتوحاً لتحديد موعد التأكيد.</p>}
                    {!loadingAppointmentSlots && appointmentSlots?.open && appointmentSlots.slots.length === 0
                      && <p className="admin-form-note">لا توجد أوقات متاحة في هذا اليوم؛ اختر تاريخاً آخر.</p>}
                    {loadingAppointmentSlots && <p className="admin-form-note" role="status">جارٍ تحميل الأوقات المتاحة...</p>}
                    <button className="admin-primary-button"
                      disabled={busyBookingId === booking.id || loadingAppointmentSlots
                        || !appointmentSlots?.open || !selectedAppointmentSlot}>
                      {busyBookingId === booking.id ? 'جارٍ التأكيد...' : 'تأكيد الموعد'}
                    </button>
                    <button className="admin-secondary-button" type="button"
                      onClick={() => setConfirmingBookingId('')}>إلغاء</button>
                  </form>
                    </section>
                  </div>,
                  document.body,
                )}
                {completingBookingId === booking.id && createPortal(
                  <div className="admin-booking-modal-backdrop" role="presentation"
                    onMouseDown={(event) => {
                      if (event.target === event.currentTarget) setCompletingBookingId('')
                    }}>
                    <section className="admin-booking-modal admin-checkout-modal" role="dialog" aria-modal="true"
                      aria-labelledby="booking-checkout-title" dir="rtl">
                      <div className="admin-booking-modal-heading">
                        <div><span className="admin-eyebrow">{booking.customerName || 'العميل'} · {booking.serviceName}</span>
                          <h2 id="booking-checkout-title">إنهاء الموعد والتحصيل</h2></div>
                        <button type="button" className="admin-icon-button" aria-label="إغلاق"
                          onClick={() => setCompletingBookingId('')}><X size={17} /></button>
                      </div>
                      {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
                      <form className="admin-checkout-form" onSubmit={(event) => void checkoutBooking(event, booking)}>
                        <div className="admin-checkout-summary">
                          <span>قيمة الخدمة</span><strong>{booking.servicePrice.toLocaleString('ar')} ₪</strong>
                          <span>إجمالي الفاتورة</span>
                          <strong>{checkoutTotal(booking.servicePrice, checkoutLines).toLocaleString('ar')} ₪</strong>
                        </div>
                        <div className="admin-checkout-products">
                          <div className="admin-panel-heading">
                            <h3>منتجات إضافية</h3>
                            <button type="button" className="admin-secondary-button"
                              disabled={!productsLoaded || !products.some((product) => product.active)}
                              onClick={() => setCheckoutLines((lines) => [...lines, {
                                key: Date.now(), productId: '', quantity: '1', unitPrice: '',
                              }])}><Plus size={15} /> إضافة منتج</button>
                          </div>
                          {!productsLoaded
                            ? <p className="admin-form-note">جارٍ تحميل منتجات المخزون...</p>
                            : !products.some((product) => product.active)
                            ? <p className="admin-form-note">أضف المنتجات أولاً من صفحة المخزون لتسجيل بيعها هنا.</p>
                            : checkoutLines.map((line) => (
                              <div className="admin-checkout-product-row" key={line.key}>
                                <label>المنتج
                                  <select required value={line.productId}
                                    onChange={(event) => {
                                      const selectedProduct = products.find((product) => product.id === event.target.value)
                                      setCheckoutLines((lines) => lines.map((item) => item.key === line.key
                                        ? {
                                            ...item,
                                            productId: event.target.value,
                                            unitPrice: selectedProduct && selectedProduct.salePrice > 0
                                              ? selectedProduct.salePrice.toFixed(2) : '',
                                          }
                                        : item))
                                    }}>
                                    <option value="" disabled>اختر منتجاً</option>
                                    {products.filter((product) => product.active).map((product) =>
                                      <option key={product.id} value={product.id}>
                                        {product.name} · المتوفر {product.quantity.toLocaleString('ar')} {product.unit}
                                      </option>)}
                                  </select>
                                </label>
                                <label>الكمية
                                  <input type="number" min="1" step="1"
                                    max={products.find((product) => product.id === line.productId)?.quantity}
                                    required value={line.quantity} disabled={!line.productId}
                                    onChange={(event) => setCheckoutLines((lines) => lines.map((item) =>
                                      item.key === line.key ? { ...item, quantity: event.target.value } : item))} />
                                </label>
                                <label>سعر الوحدة (₪)
                                  <input type="number" min="0.01" step="0.01" required
                                    value={line.unitPrice} disabled={!line.productId}
                                    onChange={(event) => setCheckoutLines((lines) => lines.map((item) =>
                                      item.key === line.key ? { ...item, unitPrice: event.target.value } : item))} />
                                </label>
                                <button type="button" className="admin-icon-button admin-icon-button--danger"
                                  aria-label="حذف المنتج من الفاتورة"
                                  onClick={() => setCheckoutLines((lines) => lines.filter((item) => item.key !== line.key))}>
                                  <Trash2 size={15} />
                                </button>
                              </div>
                            ))}
                        </div>
                        <label>المبلغ المقبوض (₪)
                          <input type="number" min="0" max={checkoutTotal(booking.servicePrice, checkoutLines)}
                            step="0.01" required value={checkoutAmount || checkoutTotal(booking.servicePrice, checkoutLines).toFixed(2)}
                            onChange={(event) => setCheckoutAmount(event.target.value)} />
                        </label>
                        <label>طريقة الدفع
                          <select value={checkoutMethod} onChange={(event) => setCheckoutMethod(event.target.value)}>
                            <option value="CASH">نقداً</option>
                            <option value="CARD">بطاقة</option>
                            <option value="TRANSFER">تحويل</option>
                            <option value="OTHER">أخرى</option>
                          </select>
                        </label>
                        <label>ملاحظات
                          <input maxLength={1000} value={checkoutNotes}
                            onChange={(event) => setCheckoutNotes(event.target.value)} />
                        </label>
                        <p className="admin-form-note">سيُسجل المبلغ غير المقبوض كدين، وتُخصم المنتجات المباعة من المخزون.</p>
                        <div className="admin-inline-actions">
                          <button className="admin-primary-button" disabled={busyBookingId === booking.id}>
                            {busyBookingId === booking.id ? 'جارٍ حفظ الفاتورة...' : 'إنهاء الحجز وحفظ الفاتورة'}
                          </button>
                          <button type="button" className="admin-secondary-button"
                            onClick={() => setCompletingBookingId('')}>إلغاء</button>
                        </div>
                      </form>
                    </section>
                  </div>,
                  document.body,
                )}
                </Fragment>
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
