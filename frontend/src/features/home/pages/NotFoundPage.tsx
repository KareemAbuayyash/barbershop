import { Link } from 'react-router-dom'

function NotFoundPage() {
  return (
    <section className="not-found page-content" aria-labelledby="not-found-title">
      <div className="not-found-code">404</div>
      <span className="eyebrow">الصفحة غير موجودة</span>
      <h1 id="not-found-title">يبدو أنك وصلت إلى طريق غير مألوف.</h1>
      <p>تحقق من الرابط أو ارجع إلى الصفحة الرئيسية.</p>
      <Link className="button button--primary" to="/">العودة إلى الرئيسية</Link>
    </section>
  )
}

export default NotFoundPage
