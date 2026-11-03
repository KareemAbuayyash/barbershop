import { useI18n } from '../../lib/i18n'

interface SectionHeadingProps {
  eyebrow: string
  title: string
  description?: string
  align?: 'start' | 'center'
}

function SectionHeading({
  eyebrow,
  title,
  description,
  align = 'start',
}: SectionHeadingProps) {
  const { t } = useI18n()
  return (
    <div className={`section-heading section-heading--${align}`}>
      <span className="eyebrow">{t(eyebrow)}</span>
      <h2>{t(title)}</h2>
      {description && <p>{t(description)}</p>}
    </div>
  )
}

export default SectionHeading
