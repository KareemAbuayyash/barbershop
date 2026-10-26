interface PageIntroProps {
  eyebrow: string
  title: string
  description: string
}

function PageIntro({ eyebrow, title, description }: PageIntroProps) {
  return (
    <div className="page-intro container">
      <span className="eyebrow">{eyebrow}</span>
      <h1>{title}</h1>
      <p>{description}</p>
    </div>
  )
}

export default PageIntro
