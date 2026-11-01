import { useState, type FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import AuthCard from '../components/AuthCard'
import { useAuth } from '../useAuth'
import { apiRequest, getErrorMessage } from '../../../lib/api'

function LoginPage() {
  const { user, isLoading, login } = useAuth()
  const navigate = useNavigate()
  const [message, setMessage] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isRegistering, setIsRegistering] = useState(false)

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

  const handleRegistration = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setMessage('')
    setIsSubmitting(true)
    const formData = new FormData(event.currentTarget)
    const username = String(formData.get('username')).trim()
    const password = String(formData.get('password'))

    try {
      await apiRequest('/api/auth/register', {
        method: 'POST',
        body: JSON.stringify({
          username,
          displayName: String(formData.get('displayName')).trim(),
          phoneNumber: String(formData.get('phoneNumber')).trim(),
          password,
        }),
      })
      const authenticatedUser = await login(username, password)
      navigate(
        authenticatedUser.authorities.includes('ROLE_CUSTOMER') ? '/my-bookings' : '/',
        { replace: true },
      )
    } catch (error) {
      setMessage(getErrorMessage(error, 'تعذر إنشاء الحساب. تحقق من البيانات وحاول مرة أخرى.'))
    } finally {
      setIsSubmitting(false)
    }
  }

  const toggleForm = () => {
    setMessage('')
    setIsRegistering((registering) => !registering)
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
        eyebrow={isRegistering ? 'مساحة العميل' : 'مرحباً بعودتك'}
        title={isRegistering ? 'إنشاء حساب عميل' : 'تسجيل الدخول'}
      >
        {isRegistering ? (
          <form className="site-form" onSubmit={(event) => void handleRegistration(event)}>
            <label htmlFor="register-username">اسم المستخدم</label>
            <input
              id="register-username"
              name="username"
              type="text"
              autoComplete="username"
              minLength={3}
              maxLength={64}
              pattern="[A-Za-z0-9._-]+"
              title="استخدم الأحرف الإنجليزية أو الأرقام أو النقطة أو الشرطة السفلية أو الشرطة."
              required
            />
            <label htmlFor="register-name">الاسم الكامل</label>
            <input
              id="register-name"
              name="displayName"
              type="text"
              autoComplete="name"
              minLength={2}
              maxLength={150}
              required
            />
            <label htmlFor="register-phone">رقم الهاتف</label>
            <input
              id="register-phone"
              name="phoneNumber"
              type="tel"
              inputMode="tel"
              autoComplete="tel"
              minLength={8}
              maxLength={32}
              pattern="[0-9+\s()-]+"
              required
            />
            <label htmlFor="register-password">كلمة المرور</label>
            <input
              id="register-password"
              name="password"
              type="password"
              autoComplete="new-password"
              minLength={8}
              maxLength={72}
              aria-describedby="password-help"
              onChange={(event) => {
                const confirmation = event.currentTarget.form?.elements.namedItem('confirmPassword')
                if (confirmation instanceof HTMLInputElement) {
                  confirmation.setCustomValidity(
                    confirmation.value === event.currentTarget.value ? '' : 'كلمتا المرور غير متطابقتين.',
                  )
                }
              }}
              required
            />
            <span className="field-help" id="password-help">يجب ألا تقل كلمة المرور عن ٨ أحرف.</span>
            <label htmlFor="register-confirm-password">تأكيد كلمة المرور</label>
            <input
              id="register-confirm-password"
              name="confirmPassword"
              type="password"
              autoComplete="new-password"
              minLength={8}
              maxLength={72}
              onChange={(event) => {
                const passwordInput = event.currentTarget.form?.elements.namedItem('password')
                if (passwordInput instanceof HTMLInputElement) {
                  event.currentTarget.setCustomValidity(
                    event.currentTarget.value === passwordInput.value ? '' : 'كلمتا المرور غير متطابقتين.',
                  )
                }
              }}
              required
            />
            <button className="button button--primary form-submit" type="submit" disabled={isSubmitting}>
              {isSubmitting ? 'جارٍ إنشاء الحساب...' : 'إنشاء حساب'}
            </button>
            {message && <p className="form-message" role="alert">{message}</p>}
            <p className="auth-switch">
              لديك حساب؟ <button className="text-button" type="button" onClick={toggleForm}>تسجيل الدخول</button>
            </p>
          </form>
        ) : (
          <>
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
            <p className="auth-switch">
              ليس لديك حساب؟ <button className="text-button" type="button" onClick={toggleForm}>إنشاء حساب</button>
            </p>
          </>
        )}
      </AuthCard>
    </div>
  )
}

export default LoginPage
