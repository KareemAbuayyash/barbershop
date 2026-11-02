import PageIntro from '../../../components/ui/PageIntro'
import AboutSection from '../components/AboutSection'

function AboutPage() {
  return (
    <div className="page-content about-page">
      <PageIntro
        eyebrow="عن BARBER HOUSE"
        title="مساحة تهتم بك، وبكل تفصيل."
        description="نؤمن أن العناية الشخصية تبدأ من الاهتمام بالتجربة، من لحظة وصولك حتى اكتمال كل تفصيل."
      />
      <AboutSection />
    </div>
  )
}

export default AboutPage
