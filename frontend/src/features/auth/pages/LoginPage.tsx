import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import AuthCard from '../components/AuthCard'

function LoginPage() {
  const [message, setMessage] = useState('')

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setMessage('تسجيل الدخول غير متاح حالياً. لم يتم إرسال بياناتك.')
  }

  return (
    <div className="page-content auth-page">
      <AuthCard
        eyebrow="مرحباً بعودتك"
        title="تسجيل الدخول"
        description="أدخل بريدك الإلكتروني أو رقم هاتفك وكلمة المرور."
      >
        <form className="site-form" onSubmit={handleSubmit}>
          <label htmlFor="login-identifier">البريد الإلكتروني أو رقم الهاتف</label>
          <input
            id="login-identifier"
            name="identifier"
            type="text"
            autoComplete="username"
            required
          />
          <label htmlFor="login-password">كلمة المرور</label>
          <input
            id="login-password"
            name="password"
            type="password"
            autoComplete="current-password"
            required
          />
          <div className="form-options">
            <Link to="/forgot-password">نسيت كلمة المرور؟</Link>
          </div>
          <button className="button button--primary form-submit" type="submit">تسجيل الدخول</button>
          {message && <p className="form-message" role="status">{message}</p>}
          <p className="auth-switch">ليس لديك حساب؟ <Link to="/register">إنشاء حساب</Link></p>
        </form>
        <p className="form-disclaimer">واجهة تجريبية فقط؛ لا يوجد تسجيل دخول أو إرسال بيانات حالياً.</p>
      </AuthCard>
    </div>
  )
}

export default LoginPage
