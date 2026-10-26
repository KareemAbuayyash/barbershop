import { Check } from 'lucide-react'
import SectionHeading from '../../../components/ui/SectionHeading'

const values = [
  'اهتمام حقيقي بالتفاصيل',
  'أجواء تمنحك وقتاً لنفسك',
  'اختيارات عناية تناسبك',
]

function AboutSection() {
  return (
    <section className="section about-section" id="about">
      <div className="container about-grid">
        <div className="about-art" aria-hidden="true">
          <div className="about-art-inner">
            <span className="about-monogram">B</span>
            <span className="about-art-brand">BARBER HOUSE</span>
            <span className="about-art-line" />
            <span className="about-art-caption">العناية تبدأ من هنا</span>
          </div>
          <span className="about-art-index">EST. —</span>
        </div>
        <div className="about-copy">
          <SectionHeading
            eyebrow="عن BARBER HOUSE"
            title="مساحة تهتم بك، وبكل تفصيل."
            description="نؤمن أن الحلاقة أكثر من مجرد موعد؛ إنها وقت تستعيد فيه توازنك، وتخرج بإحساس أفضل. لذلك نضع الجودة والدقة والراحة في قلب كل تجربة."
          />
          <ul className="about-values">
            {values.map((value) => (
              <li key={value}><Check size={15} aria-hidden="true" />{value}</li>
            ))}
          </ul>
        </div>
      </div>
    </section>
  )
}

export default AboutSection
