import { Link } from 'react-router-dom'
import { navigation, site } from '../../config/site'

function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="container footer-main">
        <Link className="brand" to="/" aria-label={`${site.brand} - إلى الأعلى`}>
          <span className="brand-mark" aria-hidden="true">B</span>
          <span className="brand-name">{site.brand}</span>
        </Link>
        <p>عناية بالتفاصيل. مساحة لوقتك.</p>
        <nav className="footer-navigation" aria-label="روابط تذييل الصفحة">
          {navigation.map((item) => (
            <Link to={item.href} key={item.href}>{item.label}</Link>
          ))}
          <Link to="/register">إنشاء حساب</Link>
          <Link to="/my-bookings">حجوزاتي</Link>
          <Link to="/login">تسجيل الدخول</Link>
        </nav>
      </div>
      <div className="container footer-bottom">
        <span>© {new Date().getFullYear()} {site.brand}. جميع الحقوق محفوظة.</span>
        <span>معلومات المحل قيد الإعداد</span>
      </div>
    </footer>
  )
}

export default SiteFooter
