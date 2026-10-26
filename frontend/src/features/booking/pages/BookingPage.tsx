import { useState } from 'react'
import { ArrowLeft } from 'lucide-react'
import { Link } from 'react-router-dom'
import PageIntro from '../../../components/ui/PageIntro'
import BookingModeCards from '../components/BookingModeCards'

function BookingPage() {
  const [selectedMode, setSelectedMode] = useState('')

  return (
    <div className="page-content booking-page">
      <PageIntro
        eyebrow="طرق الحجز"
        title="موعدك، بالطريقة التي تناسبك"
        description="اختر الطريقة التي تفضّلها لتنظيم زيارتك. هذه معاينة للخيارات المخطط لها، ولا يتم إرسال أو حفظ أي حجز."
      />
      <section className="container page-section booking-choice" aria-labelledby="booking-choice-title">
        <h2 id="booking-choice-title" className="visually-hidden">اختر طريقة الحجز</h2>
        <BookingModeCards selectedMode={selectedMode} onSelect={setSelectedMode} />
        <div className="booking-next-step" aria-live="polite">
          {selectedMode ? (
            <p>اخترت <strong>{selectedMode}</strong>. الخطوة التالية ستتوفر بعد ربط خدمة الحجز.</p>
          ) : (
            <p>اختر إحدى الطرق لمعرفة تفاصيلها.</p>
          )}
          <button className="button button--primary" type="button" disabled>
            متابعة الحجز <ArrowLeft size={16} aria-hidden="true" />
          </button>
        </div>
        <p className="booking-disclaimer">هذه الواجهة للمعاينة فقط؛ لا يتم تسجيل موعد أو إرسال طلب إلى المحل.</p>
        <Link className="text-link booking-back-link" to="/">العودة إلى الرئيسية <ArrowLeft size={15} aria-hidden="true" /></Link>
      </section>
    </div>
  )
}

export default BookingPage
