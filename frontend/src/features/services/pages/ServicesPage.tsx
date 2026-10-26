import PageIntro from '../../../components/ui/PageIntro'
import ServiceCards from '../components/ServiceCards'

function ServicesPage() {
  return (
    <div className="page-content services-page">
      <PageIntro
        eyebrow="قائمة الخدمات"
        title="عناية تُتقن التفاصيل"
        description="اكتشف خيارات العناية المتاحة. تفاصيل الخدمات والأسعار قابلة للتحديث عند اعتمادها."
      />
      <section className="container page-section" aria-label="الخدمات">
        <ServiceCards />
        <p className="sample-price-note">الأسعار المعروضة نماذج تجريبية فقط، ولا تمثل أسعاراً فعلية للمحل. المدة والأسعار قيد الاعتماد.</p>
      </section>
    </div>
  )
}

export default ServicesPage
