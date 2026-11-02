import { useEffect, useState } from 'react'
import { AlertTriangle, Bell, CalendarDays, Package, RefreshCw } from 'lucide-react'
import { Link } from 'react-router-dom'
import { apiRequest, getErrorMessage } from '../../../lib/api'
import type { AdminNotification } from '../admin-api'

function AdminNotificationsPage() {
  const [notifications, setNotifications] = useState<AdminNotification[]>([])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  const load = async () => {
    try {
      setNotifications(await apiRequest<AdminNotification[]>('/api/admin/notifications'))
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحميل الإشعارات.'))
    } finally { setLoading(false) }
  }
  useEffect(() => {
    let active = true
    void apiRequest<AdminNotification[]>('/api/admin/notifications')
      .then((data) => { if (active) setNotifications(data) })
      .catch((cause: unknown) => {
        if (active) setError(getErrorMessage(cause, 'تعذر تحميل الإشعارات.'))
      })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  return (
    <div className="admin-page">
      <section className="admin-panel">
        <div className="admin-panel-heading">
          <div><span className="admin-eyebrow">تنبيهات تحتاج متابعة</span><h2>الإشعارات</h2></div>
          <button type="button" className="admin-secondary-button" onClick={() => {
            setLoading(true)
            setError('')
            void load()
          }} disabled={loading}>
            <RefreshCw size={16} /> تحديث
          </button>
        </div>
        {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
        {loading ? <p className="admin-state" role="status">جارٍ تحميل الإشعارات...</p>
          : notifications.length ? (
            <div className="admin-notification-list">{notifications.map((item) => {
              const isStock = item.type === 'LOW_STOCK'
              return (
                <Link className="admin-notification-card" to={isStock ? '/owner/inventory' : '/owner/bookings'}
                  key={item.type}>
                  <span className="admin-stat-icon">{isStock ? <Package size={19} /> : <CalendarDays size={19} />}</span>
                  <span><strong>{item.message}</strong><small>اضغط لفتح التفاصيل</small></span>
                  <span className="admin-notification-count">{item.count.toLocaleString('ar')}</span>
                </Link>
              )
            })}</div>
          ) : <div className="admin-empty"><Bell size={24} /><h3>لا توجد تنبيهات حالية</h3>
            <p><AlertTriangle size={15} /> ستظهر هنا الحجوزات المعلقة والمخزون المنخفض.</p></div>}
      </section>
    </div>
  )
}

export default AdminNotificationsPage
