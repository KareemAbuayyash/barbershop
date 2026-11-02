import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { apiRequest, clearCsrfToken, getCsrfToken, type CurrentUser } from '../../lib/api'
import { AuthContext } from './auth-context'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    let active = true
    void apiRequest<CurrentUser>('/api/auth/me')
      .then((currentUser) => {
        if (active) setUser(currentUser)
      })
      .catch(() => {
        if (active) setUser(null)
      })
      .finally(() => {
        if (active) setIsLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  useEffect(() => {
    const handleExpiredSession = () => {
      clearCsrfToken()
      setUser(null)
    }
    window.addEventListener('barbershop:session-expired', handleExpiredSession)
    return () => window.removeEventListener('barbershop:session-expired', handleExpiredSession)
  }, [])

  const login = useCallback(async (username: string, password: string, rememberMe = false) => {
    await getCsrfToken()
    const authenticatedUser = await apiRequest<CurrentUser>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password, rememberMe }),
    })
    await getCsrfToken()
    setUser(authenticatedUser)
    return authenticatedUser
  }, [])

  const logout = useCallback(async () => {
    await apiRequest<void>('/api/auth/logout', { method: 'POST' })
    clearCsrfToken()
    await getCsrfToken()
    setUser(null)
  }, [])

  const updateUsername = useCallback((username: string) => {
    setUser((current) => current ? { ...current, username } : current)
  }, [])

  const value = useMemo(
    () => ({ user, isLoading, login, logout, updateUsername }),
    [user, isLoading, login, logout, updateUsername],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
