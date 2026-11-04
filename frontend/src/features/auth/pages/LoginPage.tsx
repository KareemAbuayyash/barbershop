import { useEffect, useState, type FormEvent } from 'react'
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { Eye, EyeOff } from 'lucide-react'
import AuthCard from '../components/AuthCard'
import { useAuth } from '../useAuth'
import { ApiError, apiRequest, getErrorMessage } from '../../../lib/api'
import { useI18n } from '../../../lib/i18n'

function LoginPage() {
  const { t } = useI18n()
  const { user, isLoading, login } = useAuth()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [message, setMessage] = useState(() =>
    searchParams.get('sessionExpired') === '1' ? 'انتهت الجلسة، يرجى تسجيل الدخول مجدداً.' : '')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isRegistering, setIsRegistering] = useState(false)
  const [showLoginPassword, setShowLoginPassword] = useState(false)
  const [credentialsError, setCredentialsError] = useState(false)

  useEffect(() => {
    if (searchParams.get('sessionExpired') === '1') {
      setMessage('انتهت الجلسة، يرجى تسجيل الدخول مجدداً.')
    }
  }, [searchParams])

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setMessage('')
    setCredentialsError(false)
    setIsSubmitting(true)
    const formData = new FormData(event.currentTarget)

    try {
      const authenticatedUser = await login(
        String(formData.get('username')),
        String(formData.get('password')),
        formData.get('rememberMe') === 'on',
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
      if (error instanceof ApiError && error.status === 401) {
        setCredentialsError(true)
        setMessage('اسم المستخدم أو كلمة المرور غير صحيحة. تأكد من كتابتهما بشكل صحيح وحاول مرة أخرى.')
      } else {
        setMessage(getErrorMessage(error, 'تعذر تسجيل الدخول. حاول مرة أخرى.'))
      }
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
    return <div className="page-content auth-page" role="status">{t('جارٍ التحقق من الجلسة...')}</div>
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
        eyebrow={t(isRegistering ? 'مساحة العميل' : 'مرحباً بعودتك')}
        title={t(isRegistering ? 'إنشاء حساب عميل' : 'تسجيل الدخول')}
      >
        {isRegistering ? (
          <form className="site-form" onSubmit={(event) => void handleRegistration(event)}>
            <label htmlFor="register-username">{t('اسم المستخدم')}</label>
            <input
              id="register-username"
              name="username"
              type="text"
              autoComplete="username"
              minLength={3}
              maxLength={64}
              pattern="[A-Za-z0-9._-]+"
              title={t('استخدم الأحرف الإنجليزية أو الأرقام أو النقطة أو الشرطة السفلية أو الشرطة.')}
              required
            />
            <label htmlFor="register-name">{t('الاسم الكامل')}</label>
            <input
              id="register-name"
              name="displayName"
              type="text"
              autoComplete="name"
              minLength={2}
              maxLength={150}
              required
            />
            <label htmlFor="register-phone">{t('رقم الهاتف')}</label>
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
            <label htmlFor="register-password">{t('كلمة المرور')}</label>
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
            <span className="field-help" id="password-help">{t('يجب ألا تقل كلمة المرور عن ٨ أحرف.')}</span>
            <label htmlFor="register-confirm-password">{t('تأكيد كلمة المرور')}</label>
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
              {isSubmitting ? t('جارٍ إنشاء الحساب...') : t('إنشاء حساب')}
            </button>
            {message && <p className="form-message" role="alert">{message}</p>}
            <p className="auth-switch">
              {t('لديك حساب؟')} <button className="text-button" type="button" onClick={toggleForm}>{t('تسجيل الدخول')}</button>
            </p>
          </form>
        ) : (
          <>
            <form className="site-form" onSubmit={handleSubmit}>
              <label htmlFor="login-username">{t('اسم المستخدم')}</label>
              <input
                id="login-username"
                name="username"
                type="text"
                autoComplete="username"
                maxLength={64}
                required
              />
              <label htmlFor="login-password">{t('كلمة المرور')}</label>
              <div className="password-input-wrap">
                <input
                  id="login-password"
                  name="password"
                  type={showLoginPassword ? 'text' : 'password'}
                  autoComplete="current-password"
                  required
                />
                <button
                  type="button"
                  className="password-visibility-toggle"
                  aria-label={t(showLoginPassword ? 'إخفاء كلمة المرور' : 'إظهار كلمة المرور')}
                  aria-pressed={showLoginPassword}
                  title={t(showLoginPassword ? 'إخفاء كلمة المرور' : 'إظهار كلمة المرور')}
                  onClick={() => setShowLoginPassword((visible) => !visible)}
                >
                  {showLoginPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                </button>
              </div>
              <label className="remember-me-option">
                <input name="rememberMe" type="checkbox" />
                {t('تذكرني لمدة 30 يوماً على هذا الجهاز')}
              </label>
              <button className="button button--primary form-submit" type="submit" disabled={isSubmitting}>
                {isSubmitting ? t('جارٍ تسجيل الدخول...') : t('تسجيل الدخول')}
              </button>
              {message && <p className={`form-message${credentialsError ? ' form-message--error' : ''}`} role="alert">{t(message)}</p>}
            </form>
            <p className="auth-switch">
              {t('ليس لديك حساب؟')} <button className="text-button" type="button" onClick={toggleForm}>{t('إنشاء حساب')}</button>
            </p>
          </>
        )}
      </AuthCard>
    </div>
  )
}

export default LoginPage
