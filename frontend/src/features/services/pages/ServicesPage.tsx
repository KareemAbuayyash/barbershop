import PageIntro from '../../../components/ui/PageIntro'
import ServiceCards from '../components/ServiceCards'

function ServicesPage() {
  return (
    <div className="page-content services-page">
      <PageIntro
        eyebrow="قائمة الخدمات"
        title="عناية تُتقن التفاصيل"
        description="تعرّف على الخدمات والأسعار الحالية في المحل."
      />
      <section className="container page-section" aria-label="الخدمات">
        <ServiceCards />
      </section>
    </div>
  )
}

export default ServicesPage
