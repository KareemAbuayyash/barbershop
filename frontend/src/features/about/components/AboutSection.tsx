import { Check } from 'lucide-react'
import { useI18n } from '../../../lib/i18n'

const values = [
  'اهتمام حقيقي بالتفاصيل',
  'أجواء تمنحك وقتاً لنفسك',
  'اختيارات عناية تناسبك',
]

function AboutSection() {
  const { t } = useI18n()
  return (
    <section className="section about-section" id="about">
      <div className="container about-grid">
        <div className="about-art" aria-hidden="true">
          <div className="about-art-inner">
            <span className="about-art-brand">BARBER HOUSE</span>
            <span className="about-art-line" />
            <span className="about-art-caption">{t('العناية تبدأ من هنا')}</span>
          </div>
          <span className="about-art-index">EST. —</span>
        </div>
        <div className="about-copy">
          <h2 className="about-values-title">{t('ما نؤمن به')}</h2>
          <p className="about-values-description">
            {t('نصنع تجربة متوازنة تجمع بين الدقة في العمل والراحة في كل زيارة.')}
          </p>
          <ul className="about-values">
            {values.map((value) => (
              <li key={value}><Check size={15} aria-hidden="true" />{t(value)}</li>
            ))}
          </ul>
        </div>
      </div>
    </section>
  )
}

export default AboutSection
