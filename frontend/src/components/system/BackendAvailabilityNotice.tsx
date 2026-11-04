import { useCallback, useEffect, useState } from 'react'
import { RefreshCw, WifiOff } from 'lucide-react'
import { useI18n } from '../../lib/i18n'

const BACKEND_RECOVERY_EVENT = 'barbershop:backend-online'

export default function BackendAvailabilityNotice() {
  const { t } = useI18n()
  const [isOffline, setIsOffline] = useState(false)
  const [isChecking, setIsChecking] = useState(false)

  const checkBackend = useCallback(async () => {
    setIsChecking(true)
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 5000)

    try {
      const response = await fetch('/api/health', {
        method: 'GET',
        headers: { Accept: 'application/json' },
        cache: 'no-store',
        credentials: 'same-origin',
        signal: controller.signal,
      })
      if (!response.ok) throw new Error('Backend health check failed.')

      const recovered = isOffline
      setIsOffline(false)
      if (recovered) window.dispatchEvent(new Event(BACKEND_RECOVERY_EVENT))
    } catch {
      setIsOffline(true)
    } finally {
      window.clearTimeout(timeout)
      setIsChecking(false)
    }
  }, [isOffline])

  useEffect(() => {
    void checkBackend()
    const interval = window.setInterval(() => void checkBackend(), 8000)
    const handleNetworkOnline = () => void checkBackend()
    window.addEventListener('online', handleNetworkOnline)
    return () => {
      window.clearInterval(interval)
      window.removeEventListener('online', handleNetworkOnline)
    }
  }, [checkBackend])

  if (!isOffline) return null

  return (
    <div className="backend-offline" role="alertdialog" aria-modal="true" aria-labelledby="backend-offline-title">
      <section className="backend-offline__card">
        <div className="backend-offline__icon" aria-hidden="true"><WifiOff size={27} strokeWidth={1.7} /></div>
        <span className="backend-offline__status"><i />{t('الخادم غير متاح')}</span>
        <h1 id="backend-offline-title">{t('تعذّر الاتصال بالخادم')}</h1>
        <p>{t('الموقع لا يستطيع الوصول إلى خادم BARBER HOUSE حالياً. تأكد من تشغيل الخادم وسيُعاد الاتصال تلقائياً.')}</p>
        <div className="backend-offline__divider" />
        <p className="backend-offline__hint">{t('انقطاع الاتصال لا يعني تسجيل خروجك. سنعيد التحقق من حسابك عند عودة الخادم.')}</p>
        <button type="button" className="button button--primary backend-offline__retry" onClick={() => void checkBackend()} disabled={isChecking}>
          <RefreshCw size={16} className={isChecking ? 'backend-offline__spin' : ''} />
          {isChecking ? t('جارٍ التحقق...') : t('إعادة المحاولة')}
        </button>
        <span className="backend-offline__auto">{t('تتم إعادة المحاولة تلقائياً كل بضع ثوانٍ')}</span>
      </section>
    </div>
  )
}
