import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../../auth/useAuth'

function CustomerRoute({ children }: { children: ReactNode }) {
  const { user, isLoading } = useAuth()

  if (isLoading) {
    return <div className="page-content auth-page" role="status">جارٍ التحقق من صلاحية الدخول...</div>
  }
  if (!user?.authorities.includes('ROLE_CUSTOMER')) {
    return <Navigate to="/login" replace />
  }
  return children
}

export default CustomerRoute
