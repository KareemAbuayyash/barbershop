import { Link } from 'react-router-dom'
import { useI18n } from '../../../lib/i18n'

function NotFoundPage() {
  const { t } = useI18n()
  return (
    <section className="not-found page-content" aria-labelledby="not-found-title">
      <div className="not-found-code">404</div>
      <span className="eyebrow">{t('الصفحة غير موجودة')}</span>
      <h1 id="not-found-title">{t('يبدو أنك وصلت إلى طريق غير مألوف.')}</h1>
      <p>{t('تحقق من الرابط أو ارجع إلى الصفحة الرئيسية.')}</p>
      <Link className="button button--primary" to="/">{t('العودة إلى الرئيسية')}</Link>
    </section>
  )
}

export default NotFoundPage
