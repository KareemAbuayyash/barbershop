import { CalendarDays } from 'lucide-react'
import { Link } from 'react-router-dom'
import PageIntro from '../../../components/ui/PageIntro'

function MyBookingsPage() {
  return (
    <div className="page-content customer-page">
      <PageIntro
        eyebrow="مساحة العميل"
        title="حجوزاتي"
        description="يمكنك الاطلاع على مواعيدك هنا بعد تفعيل الحسابات والحجوزات."
      />
      <section className="container empty-state" aria-labelledby="empty-bookings-title">
        <span className="empty-state-icon" aria-hidden="true"><CalendarDays size={25} /></span>
        <h2 id="empty-bookings-title">سجّل الدخول للمتابعة</h2>
        <p>لا نعرض حجوزات قبل ربط الحسابات بالخدمة. سجّل الدخول للوصول إلى هذه المساحة عند توفرها.</p>
        <Link className="button button--primary" to="/login">تسجيل الدخول</Link>
      </section>
    </div>
  )
}

export default MyBookingsPage
