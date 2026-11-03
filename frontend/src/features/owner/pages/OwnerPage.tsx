import { useEffect, useState } from 'react'
import { ArrowDownLeft, ArrowUpLeft, CalendarDays, CircleDollarSign, Package, Users } from 'lucide-react'
import { Link } from 'react-router-dom'
import { apiRequest, getErrorMessage } from '../../../lib/api'
import type { AdminDashboardTrendPoint } from '../admin-api'

interface DashboardSummary {
  pendingBookings: number
  bookingsToday: number
  activeCustomers: number
  collectedToday: number
  outstandingDebt: number
  lowStockProducts: number
}

const cards: Array<{
  key: keyof DashboardSummary
  label: string
  icon: typeof CalendarDays
  to: string
  money?: boolean
}> = [
  { key: 'bookingsToday', label: 'حجوزات اليوم', icon: CalendarDays, to: '/owner/bookings' },
  { key: 'pendingBookings', label: 'بانتظار التأكيد', icon: ArrowDownLeft, to: '/owner/bookings?status=PENDING' },
  { key: 'activeCustomers', label: 'العملاء', icon: Users, to: '/owner/customers' },
  { key: 'collectedToday', label: 'المتحصل اليوم (₪)', icon: ArrowUpLeft, to: '/owner/accounts', money: true },
  { key: 'outstandingDebt', label: 'الديون المستحقة (₪)', icon: CircleDollarSign, to: '/owner/accounts', money: true },
  { key: 'lowStockProducts', label: 'تنبيهات المخزون', icon: Package, to: '/owner/inventory' },
]

function OwnerPage() {
  const [summary, setSummary] = useState<DashboardSummary | null>(null)
  const [trend, setTrend] = useState<AdminDashboardTrendPoint[] | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    void apiRequest<DashboardSummary>('/api/admin/summary')
      .then((data) => { if (active) setSummary(data) })
      .catch((cause: unknown) => { if (active) setError(getErrorMessage(cause, 'تعذر تحميل ملخص الإدارة.')) })
    void apiRequest<AdminDashboardTrendPoint[]>('/api/admin/summary/trend')
      .then((data) => { if (active) setTrend(data) })
      .catch((cause: unknown) => { if (active) setError(getErrorMessage(cause, 'تعذر تحميل حركة المحل.')) })
    return () => { active = false }
  }, [])

  return (
    <div className="admin-page">
      <div className="admin-welcome">
        <div><span className="admin-eyebrow">نظرة عامة</span><h2>أهلاً بك في لوحة الإدارة</h2>
          <p>ملخص مباشر لحركة المحل والعمليات التي تحتاج متابعتك.</p></div>
        <Link className="admin-primary-button" to="/owner/bookings">عرض الحجوزات</Link>
      </div>
      {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
      {!summary ? (
        <p className="admin-state" role="status">جارٍ تحميل الملخص...</p>
      ) : (
        <div className="admin-stat-grid">
          {cards.map(({ key, label, icon: Icon, to, ...options }) => (
            <Link className="admin-stat-card" to={to} key={key}>
              <span className="admin-stat-icon"><Icon size={20} /></span>
              <span className="admin-stat-label">{label}</span>
              <strong>{options.money ? summary[key].toLocaleString('ar', { minimumFractionDigits: 2 }) : summary[key].toLocaleString('ar')}</strong>
            </Link>
          ))}
        </div>
      )}
      <section className="admin-panel admin-trend-panel">
        <div className="admin-panel-heading">
          <div><span className="admin-eyebrow">آخر سبعة أيام</span><h2>حركة المحل والتحصيل</h2></div>
          <Link className="admin-secondary-button" to="/owner/reports">التقارير المالية</Link>
        </div>
        {trend === null ? (
          <p className="admin-state" role="status">جارٍ تحميل الرسم البياني...</p>
        ) : (
          <>
            <div className="admin-trend-legend"><span /><small>الدفعات المحصلة فعلياً (₪)</small></div>
            <div className="admin-trend-chart">
              <svg viewBox="0 0 700 230" role="img" aria-label="رسم الدفعات المحصلة وعدد الحجوزات خلال آخر سبعة أيام">
                <line x1="20" y1="174" x2="680" y2="174" className="admin-trend-axis" />
                {trend.map((point, index) => {
                  const maxCollected = Math.max(...trend.map(({ collected }) => collected), 1)
                  const height = Math.max(point.collected > 0 ? 3 : 0, (point.collected / maxCollected) * 128)
                  const x = 28 + index * 94
                  const dateLabel = new Intl.DateTimeFormat('ar', { day: 'numeric', month: 'short' })
                    .format(new Date(`${point.date}T12:00:00`))
                  return (
                    <g key={point.date}>
                      <rect className="admin-trend-bar" x={x} y={174 - height} width="46" height={height} rx="5">
                        <title>{`${dateLabel}: ${point.collected.toLocaleString('ar')} ₪، ${point.bookings.toLocaleString('ar')} حجز`}</title>
                      </rect>
                      <text className="admin-trend-date" x={x + 23} y="197" textAnchor="middle">{dateLabel}</text>
                      <text className="admin-trend-count" x={x + 23} y="218" textAnchor="middle">
                        {point.bookings.toLocaleString('ar')} حجوزات
                      </text>
                    </g>
                  )
                })}
              </svg>
            </div>
            <p className="admin-form-note">يعرض الرسم الدفعات المسجلة فعلياً؛ لا تُحتسب الحجوزات أو الديون غير المحصلة كإيراد.</p>
          </>
        )}
      </section>
      <section className="admin-panel admin-shortcuts">
        <div className="admin-panel-heading"><div><span className="admin-eyebrow">الوصول السريع</span><h2>إدارة المحل</h2></div></div>
        <div className="admin-shortcut-grid">
          <Link to="/owner/bookings">مراجعة الحجوزات <span>←</span></Link>
          <Link to="/owner/accounts">الحسابات والدفعات <span>←</span></Link>
          <Link to="/owner/expenses">إضافة مصروف <span>←</span></Link>
          <Link to="/owner/inventory">تحديث المخزون <span>←</span></Link>
        </div>
      </section>
    </div>
  )
}

export default OwnerPage
