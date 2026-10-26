import { Clock3, MapPin, Phone } from 'lucide-react'
import { Link } from 'react-router-dom'
import SectionHeading from '../../../components/ui/SectionHeading'

const contactDetails = [
  {
    label: 'الهاتف',
    value: 'يُضاف لاحقاً',
    icon: Phone,
  },
  {
    label: 'العنوان',
    value: 'يُحدد لاحقاً',
    icon: MapPin,
  },
  {
    label: 'ساعات العمل',
    value: 'تُحدد لاحقاً',
    icon: Clock3,
  },
]

function ContactSection() {
  return (
    <section className="section contact-section" id="contact">
      <div className="container contact-grid">
        <div className="contact-copy">
          <SectionHeading
            eyebrow="تواصل معنا"
            title="يسعدنا أن نراك."
            description="نعمل على تجهيز تفاصيل التواصل والزيارة. ستجدها هنا قريباً."
          />
          <Link className="contact-cta" to="/services">
            تعرّف على خدماتنا
            <span aria-hidden="true">←</span>
          </Link>
        </div>
        <div className="contact-details">
          {contactDetails.map(({ label, value, icon: Icon }) => (
            <div className="contact-detail" key={label}>
              <span className="contact-detail-icon" aria-hidden="true"><Icon size={18} strokeWidth={1.5} /></span>
              <div>
                <span>{label}</span>
                <strong>{value}</strong>
              </div>
            </div>
          ))}
          <p className="contact-placeholder-note">بيانات التواصل والموقع قيد الإعداد — لا يوجد عنوان أو رقم منشور حالياً.</p>
        </div>
      </div>
    </section>
  )
}

export default ContactSection
