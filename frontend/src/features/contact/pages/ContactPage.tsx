import PageIntro from '../../../components/ui/PageIntro'
import ContactSection from '../components/ContactSection'

function ContactPage() {
  return (
    <div className="page-content contact-page">
      <PageIntro
        eyebrow="تواصل معنا"
        title="يسعدنا أن نراك."
        description="نعمل على تجهيز بيانات التواصل ومعلومات الزيارة، وستظهر هنا عند اعتمادها."
      />
      <ContactSection />
    </div>
  )
}

export default ContactPage
