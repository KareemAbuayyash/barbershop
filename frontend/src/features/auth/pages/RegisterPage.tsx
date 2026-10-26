import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import AuthCard from '../components/AuthCard'

function RegisterPage() {
  const [message, setMessage] = useState('')

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setMessage('إنشاء الحساب غير متاح حالياً. لم يتم إنشاء حساب أو إرسال بيانات.')
  }

  return (
    <div className="page-content auth-page">
      <AuthCard
        eyebrow="انضم إلينا"
        title="إنشاء حساب"
        description="املأ البيانات التالية. إنشاء الحساب غير متاح حتى ربط الخدمة الخلفية."
      >
        <form className="site-form" onSubmit={handleSubmit}>
          <label htmlFor="register-name">الاسم الكامل</label>
          <input id="register-name" name="name" type="text" autoComplete="name" minLength={2} required />
          <label htmlFor="register-phone">رقم الهاتف</label>
          <input
            id="register-phone"
            name="phone"
            type="tel"
            inputMode="tel"
            autoComplete="tel"
            pattern="[0-9+\s()-]{8,20}"
            title="أدخل رقم هاتف من ٨ إلى ٢٠ خانة، باستخدام الأرقام أو + أو المسافات أو الأقواس أو الشرطة."
            required
          />
          <label htmlFor="register-password">كلمة المرور</label>
          <input
            id="register-password"
            name="password"
            type="password"
            autoComplete="new-password"
            minLength={8}
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
            onChange={(event) => {
              const password = event.currentTarget.form?.elements.namedItem('password')
              if (password instanceof HTMLInputElement) {
                event.currentTarget.setCustomValidity(
                  event.currentTarget.value === password.value ? '' : 'كلمتا المرور غير متطابقتين.',
                )
              }
            }}
            required
          />
          <button className="button button--primary form-submit" type="submit">إنشاء حساب</button>
          {message && <p className="form-message" role="status">{message}</p>}
          <p className="auth-switch">لديك حساب؟ <Link to="/login">تسجيل الدخول</Link></p>
        </form>
      </AuthCard>
    </div>
  )
}

export default RegisterPage
