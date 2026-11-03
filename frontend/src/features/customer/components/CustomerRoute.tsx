import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../../auth/useAuth'
import { useI18n } from '../../../lib/i18n'

function CustomerRoute({ children }: { children: ReactNode }) {
  const { user, isLoading } = useAuth()
  const { t } = useI18n()

  if (isLoading) {
    return <div className="page-content auth-page" role="status">{t('جارٍ التحقق من صلاحية الدخول...')}</div>
  }
  if (!user?.authorities.includes('ROLE_CUSTOMER')) {
    return <Navigate to="/login" replace />
  }
  return children
}

export default CustomerRoute
