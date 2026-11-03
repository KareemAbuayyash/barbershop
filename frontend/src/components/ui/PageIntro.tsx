import { useI18n } from '../../lib/i18n'

interface PageIntroProps {
  eyebrow: string
  title: string
  description: string
}

function PageIntro({ eyebrow, title, description }: PageIntroProps) {
  const { t } = useI18n()
  return (
    <div className="page-intro container">
      <span className="eyebrow">{t(eyebrow)}</span>
      <h1>{t(title)}</h1>
      <p>{t(description)}</p>
    </div>
  )
}

export default PageIntro
