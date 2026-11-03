import { Link } from 'react-router-dom'
import { navigation, site } from '../../config/site'
import { useI18n } from '../../lib/i18n'

function SiteFooter() {
  const { t } = useI18n()
  return (
    <footer className="site-footer">
      <div className="container footer-main">
        <Link className="brand" to="/" aria-label={`${site.brand} - ${t('إلى الأعلى')}`}>
          <span className="brand-mark" aria-hidden="true">B</span>
          <span className="brand-name">{site.brand}</span>
        </Link>
        <p>{t('عناية بالتفاصيل. مساحة لوقتك.')}</p>
        <nav className="footer-navigation" aria-label={t('روابط تذييل الصفحة')}>
          {navigation.map((item) => (
            <Link to={item.href} key={item.href}>{t(item.label)}</Link>
          ))}
          <Link to="/my-bookings">{t('حجوزاتي')}</Link>
          <Link to="/login">{t('تسجيل الدخول')}</Link>
        </nav>
      </div>
      <div className="container footer-bottom">
        <span>© {new Date().getFullYear()} {site.brand}. {t('جميع الحقوق محفوظة.')}</span>
        <span>{t('معلومات المحل قيد الإعداد')}</span>
      </div>
    </footer>
  )
}

export default SiteFooter
