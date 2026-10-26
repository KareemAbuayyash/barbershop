import { useState } from 'react'
import { ArrowLeft, ArrowUpLeft, Scissors } from 'lucide-react'
import { site } from '../../../config/site'
import Button from '../../../components/ui/Button'

function HeroSection() {
  const [imageFailed, setImageFailed] = useState(false)

  return (
    <section className="hero-section" id="home" aria-labelledby="hero-title">
      <div className="container hero-grid">
        <div className="hero-copy">
          <span className="eyebrow hero-eyebrow">
            <span className="eyebrow-line" aria-hidden="true" />
            تجربة عناية على مهل
          </span>
          <h1 id="hero-title">
            أناقة تبدأ
            <br />
            <span>من التفاصيل.</span>
          </h1>
          <p className="hero-description">
            مساحة تجمع بين الحرفة والراحة، لتعيش تجربة حلاقة متقنة
            وإطلالة تعبّر عنك.
          </p>
          <div className="hero-actions">
            <Button to="/booking">
              احجز موعدك
              <ArrowLeft size={16} aria-hidden="true" />
            </Button>
            <Button to="/services" variant="secondary">
              استكشف خدماتنا
              <ArrowUpLeft size={15} aria-hidden="true" />
            </Button>
          </div>
          <div className="hero-note">
            <span className="hero-note-icon" aria-hidden="true"><Scissors size={16} /></span>
            <span>وقتك يستحق عناية مختلفة</span>
          </div>
        </div>

        <div className={`hero-visual${imageFailed ? ' hero-visual--fallback' : ''}`}>
          {!imageFailed ? (
            <img
              className="hero-image"
              src={site.image}
              alt="حلاق يعتني بزبون أثناء جلسة حلاقة"
              onError={() => setImageFailed(true)}
            />
          ) : (
            <div className="hero-image-fallback" role="img" aria-label="صورة تجربة الحلاقة غير متاحة">
              <Scissors size={46} strokeWidth={1} aria-hidden="true" />
              <span>{site.brand}</span>
            </div>
          )}
          <div className="hero-image-shade" aria-hidden="true" />
          <div className="hero-image-caption">
            <span className="caption-index">01 / 04</span>
            <span>فنّ الحلاقة، كما يجب أن يكون</span>
          </div>
          <div className="hero-side-label" aria-hidden="true">تجربة تليق بك</div>
          <span className="hero-frame-corner" aria-hidden="true" />
        </div>
      </div>
      <div className="hero-bottom container" aria-hidden="true">
        <span>حرفة. دقة. راحة.</span>
        <span className="hero-bottom-line" />
        <span>BARBER HOUSE</span>
      </div>
    </section>
  )
}

export default HeroSection
