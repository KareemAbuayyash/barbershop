import { useState, type FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { Link } from 'react-router-dom'
import AuthCard from '../components/AuthCard'
import { useAuth } from '../useAuth'
import { getErrorMessage } from '../../../lib/api'

function LoginPage() {
  const { user, isLoading, login } = useAuth()
  const navigate = useNavigate()
  const [message, setMessage] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setMessage('')
    setIsSubmitting(true)
    const formData = new FormData(event.currentTarget)

    try {
      const authenticatedUser = await login(
        String(formData.get('username')),
        String(formData.get('password')),
      )
      navigate(
        authenticatedUser.authorities.includes('ROLE_OWNER')
          ? '/owner'
          : authenticatedUser.authorities.includes('ROLE_CUSTOMER')
            ? '/my-bookings'
            : '/',
        { replace: true },
      )
    } catch (error) {
      setMessage(getErrorMessage(error, 'تعذر تسجيل الدخول. حاول مرة أخرى.'))
    } finally {
      setIsSubmitting(false)
    }
  }

  if (isLoading) {
    return <div className="page-content auth-page" role="status">جارٍ التحقق من الجلسة...</div>
  }
  if (user?.authorities.includes('ROLE_OWNER')) {
    return <Navigate to="/owner" replace />
  }
  if (user?.authorities.includes('ROLE_CUSTOMER')) {
    return <Navigate to="/my-bookings" replace />
  }

  return (
    <div className="page-content auth-page">
      <AuthCard
        eyebrow="مرحباً بعودتك"
        title="تسجيل الدخول"
        description="أدخل بيانات حسابك، وسيتم توجيهك تلقائياً حسب صلاحياته."
      >
        <form className="site-form" onSubmit={handleSubmit}>
          <label htmlFor="login-username">اسم المستخدم</label>
          <input
            id="login-username"
            name="username"
            type="text"
            autoComplete="username"
            maxLength={64}
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
          <button className="button button--primary form-submit" type="submit" disabled={isSubmitting}>
            {isSubmitting ? 'جارٍ تسجيل الدخول...' : 'تسجيل الدخول'}
          </button>
          {message && <p className="form-message" role="alert">{message}</p>}
        </form>
        <p className="form-disclaimer">حسابات العملاء متاحة للتسجيل، أما حساب المالك فيُجهّز من إعدادات النظام فقط.</p>
        <p className="auth-switch">ليس لديك حساب؟ <Link to="/register">إنشاء حساب عميل</Link></p>
      </AuthCard>
    </div>
  )
}

export default LoginPage
