import { Bell } from 'lucide-react'
import { Link } from 'react-router-dom'

function CustomerSettingsPage() {
  return (
    <section className="customer-settings-page" dir="rtl">
      <span>حسابي</span>
      <h1>الإعدادات</h1>
      <Link className="settings-notification-link" to="/my-settings/notifications">
        <Bell size={20} />
        <span><strong>إعدادات الإشعارات</strong><small>تحكم بإشعارات حسابك وأجهزتك</small></span>
      </Link>
    </section>
  )
}

export default CustomerSettingsPage
