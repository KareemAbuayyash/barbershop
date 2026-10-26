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
      <section className="container about-principles">
        <h2>ما نؤمن به</h2>
        <p>الجودة في الاختيار، والدقة في التنفيذ، والراحة في كل زيارة. نعمل على أن تكون تجربتك واضحة ومريحة، دون وعود تتجاوز ما نقدمه.</p>
      </section>
    </div>
  )
}

export default AboutPage
