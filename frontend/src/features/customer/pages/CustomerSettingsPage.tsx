import { Bell } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useI18n } from '../../../lib/i18n'

function CustomerSettingsPage() {
  const { t } = useI18n()
  return (
    <section className="customer-settings-page">
      <span>{t('حسابي')}</span>
      <h1>{t('الإعدادات')}</h1>
      <Link className="settings-notification-link" to="/my-settings/notifications">
        <Bell size={20} />
        <span><strong>{t('إعدادات الإشعارات')}</strong><small>{t('تحكم بإشعارات حسابك وأجهزتك')}</small></span>
      </Link>
    </section>
  )
}

export default CustomerSettingsPage
