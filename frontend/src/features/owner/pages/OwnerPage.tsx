import { useEffect, useMemo, useState } from 'react'
import {
  ArrowDownLeft,
  ArrowLeft,
  ArrowUpLeft,
  CalendarDays,
  CircleDollarSign,
  Clock3,
  Package,
  ReceiptText,
  Users,
} from 'lucide-react'
import { Link } from 'react-router-dom'
import { apiRequest, getErrorMessage, type Booking, type BookingStatus } from '../../../lib/api'
import { getShopDate } from '../../../lib/shop-time'
import type { AdminBooking, AdminDashboardTrendPoint, AdminPage } from '../admin-api'

interface DashboardSummary {
  pendingBookings: number
  bookingsToday: number
  activeCustomers: number
  collectedToday: number
  outstandingDebt: number
  lowStockProducts: number
}

const statusLabels: Record<BookingStatus, string> = {
  PENDING: 'بانتظار التأكيد',
  CONFIRMED: 'مؤكد',
  COMPLETED: 'مكتمل',
  CANCELLED: 'ملغي',
}

const actions = [
  { label: 'الحجوزات', description: 'مراجعة الطلبات والأدوار', to: '/owner/bookings', icon: CalendarDays },
  { label: 'الحسابات', description: 'الدفعات والأرصدة المستحقة', to: '/owner/accounts', icon: CircleDollarSign },
  { label: 'مصروف جديد', description: 'إضافة مصروف للمحل', to: '/owner/expenses', icon: ReceiptText },
  { label: 'المخزون', description: 'المنتجات وحركاتها', to: '/owner/inventory', icon: Package },
]

function formatAmount(value: number) {
  return value.toLocaleString('ar', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function formatBookingTime(booking: Booking) {
  if (booking.startsAt) {
    return new Intl.DateTimeFormat('ar', {
      timeZone: 'Asia/Hebron', hour: 'numeric', minute: '2-digit',
    }).format(new Date(booking.startsAt))
  }
  if (booking.queuePosition !== null) return `الدور ${booking.queuePosition.toLocaleString('ar')}`
  return 'بانتظار تحديد الموعد'
}

function OwnerPage() {
  const [summary, setSummary] = useState<DashboardSummary | null>(null)
  const [trend, setTrend] = useState<AdminDashboardTrendPoint[] | null>(null)
  const [todayBookings, setTodayBookings] = useState<AdminBooking[] | null>(null)
  const [hoveredTrendPoint, setHoveredTrendPoint] = useState<number | null>(null)
  const [trendTooltipPosition, setTrendTooltipPosition] = useState({ x: 0, y: 0 })
  const [error, setError] = useState('')
  const shopDate = getShopDate()
  const dateLabel = new Intl.DateTimeFormat('ar', {
    timeZone: 'Asia/Hebron', weekday: 'long', day: 'numeric', month: 'long',
  }).format(new Date(`${shopDate}T12:00:00`))

  useEffect(() => {
    let active = true
    void apiRequest<DashboardSummary>('/api/admin/summary')
      .then((data) => { if (active) setSummary(data) })
      .catch((cause: unknown) => { if (active) setError(getErrorMessage(cause, 'تعذر تحميل ملخص الإدارة.')) })
    void apiRequest<AdminDashboardTrendPoint[]>('/api/admin/summary/trend')
      .then((data) => { if (active) setTrend(data) })
      .catch((cause: unknown) => { if (active) setError(getErrorMessage(cause, 'تعذر تحميل حركة المحل.')) })
    const params = new URLSearchParams({
      page: '0', size: '5', fromDate: shopDate, toDate: shopDate, activeOnly: 'true',
    })
    void apiRequest<AdminPage<AdminBooking>>(`/api/bookings?${params}`)
      .then((data) => { if (active) setTodayBookings(data.content) })
      .catch((cause: unknown) => { if (active) setError(getErrorMessage(cause, 'تعذر تحميل حجوزات اليوم.')) })
    return () => { active = false }
  }, [shopDate])

  const chart = useMemo(() => {
    if (!trend?.length) return null
    const maximum = Math.max(...trend.map(({ collected }) => collected), 1)
    const points = trend.map((point, index) => ({
      ...point,
      x: trend.length === 1 ? 350 : 38 + index * (624 / (trend.length - 1)),
      y: 178 - (point.collected / maximum) * 132,
      label: new Intl.DateTimeFormat('ar', { day: 'numeric', month: 'short' })
        .format(new Date(`${point.date}T12:00:00`)),
    }))
    const line = points.map(({ x, y }) => `${x},${y}`).join(' ')
    const area = `38,178 ${line} 662,178`
    return { points, line, area }
  }, [trend])

  const weekCollected = trend?.reduce((total, point) => total + point.collected, 0) ?? 0

  return (
    <div className="admin-page admin-dashboard-page">
      <div className="admin-dashboard-heading">
        <div><span className="admin-eyebrow">نظرة عامة</span><h2>حركة المحل اليوم</h2></div>
        <span className="admin-dashboard-date"><CalendarDays size={15} /> {dateLabel}</span>
      </div>
      {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}

      {summary ? (
        <div className="admin-dashboard-stat-grid">
          <Link className="admin-dashboard-stat-card admin-dashboard-stat-card--pink" to="/owner/bookings">
            <span>حجوزات اليوم</span><CalendarDays size={19} /><strong>{summary.bookingsToday.toLocaleString('ar')}</strong>
            <small>عرض قائمة الحجوزات <ArrowLeft size={13} /></small>
          </Link>
          <Link className="admin-dashboard-stat-card admin-dashboard-stat-card--purple" to="/owner/bookings?status=PENDING">
            <span>بانتظار التأكيد</span><ArrowDownLeft size={19} /><strong>{summary.pendingBookings.toLocaleString('ar')}</strong>
            <small>طلبات تحتاج متابعة <ArrowLeft size={13} /></small>
          </Link>
          <Link className="admin-dashboard-stat-card admin-dashboard-stat-card--blue" to="/owner/accounts">
            <span>المتحصل اليوم · ₪</span><ArrowUpLeft size={19} /><strong>{formatAmount(summary.collectedToday)}</strong>
            <small>الدفعات المسجلة اليوم <ArrowLeft size={13} /></small>
          </Link>
          <Link className="admin-dashboard-stat-card admin-dashboard-stat-card--orange" to="/owner/customers">
            <span>العملاء</span><Users size={19} /><strong>{summary.activeCustomers.toLocaleString('ar')}</strong>
            <small>إجمالي العملاء <ArrowLeft size={13} /></small>
          </Link>
        </div>
      ) : <div className="admin-dashboard-loading" role="status">جارٍ تحميل مؤشرات المحل...</div>}

      <div className="admin-dashboard-analytics-grid">
        <section className="admin-panel admin-dashboard-earnings">
          <div className="admin-dashboard-panel-heading">
            <div><span className="admin-eyebrow">آخر سبعة أيام</span><h2>التحصيل الفعلي</h2></div>
            <Link className="admin-secondary-button" to="/owner/reports">التقارير المالية</Link>
          </div>
          {trend === null ? <p className="admin-state" role="status">جارٍ تحميل حركة التحصيل...</p> : (
            <>
              <div className="admin-dashboard-earnings-total">
                <strong>{formatAmount(weekCollected)} <small>₪</small></strong>
                <span>إجمالي الدفعات المحصلة خلال أسبوع</span>
              </div>
              <div className="admin-dashboard-line-chart">
                {chart && <svg viewBox="0 0 700 230" role="img" aria-label="الدفعات المحصلة خلال آخر سبعة أيام"
                  onMouseMove={(event) => {
                    const bounds = event.currentTarget.getBoundingClientRect()
                    setTrendTooltipPosition({
                      x: Math.min(bounds.width - 95, Math.max(95, event.clientX - bounds.left)),
                      y: Math.max(32, event.clientY - bounds.top),
                    })
                  }}
                  onMouseLeave={() => setHoveredTrendPoint(null)}>
                  <defs>
                    <linearGradient id="dashboard-area" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="#c3a66b" stopOpacity=".3" />
                      <stop offset="100%" stopColor="#c3a66b" stopOpacity="0" />
                    </linearGradient>
                  </defs>
                  {[46, 90, 134, 178].map((y) => <line key={y} x1="38" y1={y} x2="662" y2={y}
                    className={y === 178 ? 'admin-dashboard-chart-axis' : 'admin-dashboard-chart-grid'} />)}
                  <polygon points={chart.area} fill="url(#dashboard-area)" />
                  <polyline points={chart.line} className="admin-dashboard-chart-hit-line"
                    onMouseMove={(event) => {
                      const bounds = event.currentTarget.ownerSVGElement?.getBoundingClientRect()
                      if (!bounds) return
                      const x = (event.clientX - bounds.left) / bounds.width * 700
                      const y = (event.clientY - bounds.top) / bounds.height * 230
                      const nearestIndex = chart.points.reduce((nearest, point, index) => {
                        const currentDistance = Math.hypot(point.x - x, point.y - y)
                        const nearestPoint = chart.points[nearest]
                        return currentDistance < Math.hypot(nearestPoint.x - x, nearestPoint.y - y) ? index : nearest
                      }, 0)
                      setHoveredTrendPoint(nearestIndex)
                    }} />
                  <polyline points={chart.line} className="admin-dashboard-chart-line" />
                  {chart.points.map((point, index) => <g key={point.date}>
                    <circle cx={point.x} cy={point.y} r="13" className="admin-dashboard-chart-hit-dot"
                      tabIndex={0} role="button"
                      aria-label={`${point.label}: ${formatAmount(point.collected)} ₪، ${point.bookings.toLocaleString('ar')} حجوزات`}
                      onMouseEnter={() => setHoveredTrendPoint(index)}
                      onFocus={() => setHoveredTrendPoint(index)} onBlur={() => setHoveredTrendPoint(null)}
                      onClick={() => setHoveredTrendPoint((active) => active === index ? null : index)}>
                      <title>{`${point.label}: ${formatAmount(point.collected)} ₪ · ${point.bookings.toLocaleString('ar')} حجوزات`}</title>
                    </circle>
                    <circle cx={point.x} cy={point.y} r="3.5" className="admin-dashboard-chart-dot" />
                    <text x={point.x} y="201" textAnchor="middle" className="admin-dashboard-chart-label">{point.label}</text>
                  </g>)}
                </svg>}
                {chart && hoveredTrendPoint !== null && chart.points[hoveredTrendPoint] && <div className="admin-dashboard-chart-tooltip"
                  style={{ left: trendTooltipPosition.x, top: trendTooltipPosition.y }} role="status">
                  <strong>{chart.points[hoveredTrendPoint].label}</strong>
                  <span>{formatAmount(chart.points[hoveredTrendPoint].collected)} ₪</span>
                  <small>{chart.points[hoveredTrendPoint].bookings.toLocaleString('ar')} حجوزات</small>
                </div>}
              </div>
              <p className="admin-form-note">الإيراد يعرض الدفعات المستلمة فعليًا، ولا يشمل الحجوزات أو الديون غير المحصلة.</p>
            </>
          )}
        </section>

        <section className="admin-panel admin-dashboard-followup">
          <div className="admin-dashboard-panel-heading">
            <div><span className="admin-eyebrow">نظرة سريعة</span><h2>تحتاج متابعة</h2></div>
          </div>
          {summary ? <div className="admin-dashboard-followup-list">
            <Link to="/owner/bookings?status=PENDING" className="admin-dashboard-followup-row">
              <span className="admin-dashboard-followup-icon followup-pending"><CalendarDays size={17} /></span>
              <span><strong>حجوزات بانتظار التأكيد</strong><small>طلبات جديدة من العملاء</small></span>
              <b>{summary.pendingBookings.toLocaleString('ar')}</b>
            </Link>
            <Link to="/owner/accounts" className="admin-dashboard-followup-row">
              <span className="admin-dashboard-followup-icon followup-debt"><CircleDollarSign size={17} /></span>
              <span><strong>الديون المستحقة</strong><small>إجمالي الرصيد غير المسدد</small></span>
              <b>{formatAmount(summary.outstandingDebt)} ₪</b>
            </Link>
            <Link to="/owner/inventory" className="admin-dashboard-followup-row">
              <span className="admin-dashboard-followup-icon followup-stock"><Package size={17} /></span>
              <span><strong>تنبيهات المخزون</strong><small>منتجات عند حد التنبيه</small></span>
              <b>{summary.lowStockProducts.toLocaleString('ar')}</b>
            </Link>
          </div> : <p className="admin-state" role="status">جارٍ تحميل التنبيهات...</p>}
        </section>
      </div>

      <div className="admin-dashboard-lower-grid">
        <section className="admin-panel admin-dashboard-appointments">
          <div className="admin-dashboard-panel-heading">
            <div><span className="admin-eyebrow">اليوم</span><h2>مواعيد وحجوزات اليوم</h2></div>
            <Link className="admin-text-link" to="/owner/bookings">عرض الكل <ArrowLeft size={14} /></Link>
          </div>
          {todayBookings === null ? <p className="admin-state" role="status">جارٍ تحميل حجوزات اليوم...</p>
            : todayBookings.length ? <div className="admin-dashboard-booking-list">
              {todayBookings.map((booking) => (
                <Link className="admin-dashboard-booking-row" to="/owner/bookings" key={booking.id}>
                  <span className="admin-dashboard-booking-time"><Clock3 size={14} /> {formatBookingTime(booking)}</span>
                  <span className="admin-dashboard-booking-client"><strong>{booking.customerName || 'عميل'}</strong>
                    <small>{booking.serviceName}</small></span>
                  <span className={`admin-dashboard-booking-status booking-status--${booking.status.toLowerCase()}`}>
                    {statusLabels[booking.status]}
                  </span>
                </Link>
              ))}
            </div> : <div className="admin-empty admin-dashboard-empty"><CalendarDays size={23} />
              <h3>لا توجد حجوزات نشطة اليوم</h3><p>ستظهر الحجوزات اليومية هنا.</p></div>}
        </section>

        <section className="admin-panel admin-dashboard-actions">
          <div className="admin-dashboard-panel-heading">
            <div><span className="admin-eyebrow">الوصول السريع</span><h2>إدارة المحل</h2></div>
          </div>
          <div className="admin-dashboard-action-list">
            {actions.map(({ label, description, to, icon: Icon }) => (
              <Link to={to} className="admin-dashboard-action-row" key={to}>
                <span><Icon size={17} /></span><div><strong>{label}</strong><small>{description}</small></div>
                <ArrowLeft size={15} />
              </Link>
            ))}
          </div>
        </section>
      </div>
    </div>
  )
}

export default OwnerPage
