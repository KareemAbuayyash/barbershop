import { useEffect, useState } from 'react'
import { CalendarDays } from 'lucide-react'
import { Link } from 'react-router-dom'
import PageIntro from '../../../components/ui/PageIntro'
import { apiRequest, getErrorMessage, type CustomerBooking } from '../../../lib/api'
import { formatShopDateTime } from '../../../lib/shop-time'

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
  const [bookings, setBookings] = useState<CustomerBooking[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    void apiRequest<CustomerBooking[]>('/api/customer/bookings')
      .then((result) => {
        if (active) setBookings(result)
      })
      .catch((cause: unknown) => {
        if (active) setError(getErrorMessage(cause, 'تعذر تحميل حجوزاتك. حاول مرة أخرى.'))
      })
      .finally(() => {
        if (active) setIsLoading(false)
      })

    return () => {
      active = false
    }
  }, [])

  return (
    <div className="page-content customer-page">
      <PageIntro
        eyebrow="مساحة العميل"
        title="حجوزاتي"
        description="تابع حالة حجوزاتك ومواعيدك في المحل."
      />
      <section className="container customer-bookings" aria-label="قائمة حجوزاتي">
        {isLoading ? (
          <p className="api-state" role="status">جارٍ تحميل حجوزاتك...</p>
        ) : error ? (
          <p className="api-state api-state--error" role="alert">{error}</p>
        ) : bookings.length === 0 ? (
          <div className="empty-state">
            <span className="empty-state-icon" aria-hidden="true"><CalendarDays size={25} /></span>
            <h2>لا توجد حجوزات بعد</h2>
            <p>يمكنك إرسال طلب حجز جديد، وستظهر تحديثاته هنا.</p>
            <Link className="button button--primary" to="/booking">احجز موعدك</Link>
          </div>
        ) : (
          <div className="customer-booking-list">
            {bookings.map((booking) => (
              <article className="owner-booking" key={booking.id}>
                <div className="owner-booking-heading">
                  <div>
                    <h2>{booking.serviceName}</h2>
                    <p>{booking.servicePrice} ₪ · {booking.serviceDurationMinutes} دقيقة</p>
                  </div>
                  <span className={`booking-status booking-status--${booking.status.toLowerCase()}`}>
                    {bookingStatuses[booking.status]}
                  </span>
                </div>
                <dl className="owner-booking-details">
                  <div><dt>طريقة الحجز</dt><dd>{bookingModes[booking.bookingMode]}</dd></div>
                  <div>
                    <dt>{booking.queueDate ? 'تاريخ الدور' : 'الموعد'}</dt>
                    <dd>{booking.queueDate
                      ? new Intl.DateTimeFormat('ar', { timeZone: 'Asia/Hebron', dateStyle: 'medium' })
                        .format(new Date(`${booking.queueDate}T12:00:00+02:00`))
                      : formatShopDateTime(booking.startsAt)}
                    </dd>
                  </div>
                  {booking.queuePosition && <div><dt>رقم الدور</dt><dd>{booking.queuePosition}</dd></div>}
                </dl>
              </article>
            ))}
          </div>
        )}
      </section>
    </div>
  )
}

export default MyBookingsPage
