import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import AuthCard from '../components/AuthCard'
import { useI18n } from '../../../lib/i18n'

function ForgotPasswordPage() {
  const { t } = useI18n()
  const [message, setMessage] = useState('')

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setMessage('إعادة تعيين كلمة المرور غير متاحة حالياً. لم يتم إرسال طلب.')
  }

  return (
    <div className="page-content auth-page">
      <AuthCard
        eyebrow={t('استعادة الوصول')}
        title={t('نسيت كلمة المرور؟')}
        description={t('أدخل بريدك الإلكتروني أو رقم هاتفك لطلب إعادة التعيين عند تفعيل الخدمة.')}
      >
        <form className="site-form" onSubmit={handleSubmit}>
          <label htmlFor="reset-identifier">{t('البريد الإلكتروني أو رقم الهاتف')}</label>
          <input id="reset-identifier" name="identifier" type="text" autoComplete="username" required />
          <button className="button button--primary form-submit" type="submit">{t('طلب إعادة التعيين')}</button>
          {message && <p className="form-message" role="status">{message}</p>}
          <p className="auth-switch"><Link to="/login">{t('العودة إلى تسجيل الدخول')}</Link></p>
        </form>
        <p className="form-disclaimer">{t('النموذج للعرض فقط؛ لن يتم إرسال أي طلب.')}</p>
      </AuthCard>
    </div>
  )
}

export default ForgotPasswordPage
