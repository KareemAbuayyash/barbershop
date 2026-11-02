import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { ApiError, apiRequest, clearCsrfToken, getCsrfToken, type CurrentUser } from '../../lib/api'
import { AuthContext } from './auth-context'

const PUSH_PERMISSION_PROMPTED = 'barber-house-push-permission-prompted'

function applicationKey(value: string) {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/')
    .padEnd(Math.ceil(value.length / 4) * 4, '=')
  return Uint8Array.from(window.atob(normalized), (character) => character.charCodeAt(0))
}

function subscriptionUsesApplicationKey(subscription: PushSubscription, publicKey: string) {
  const currentKey = subscription.options.applicationServerKey
  if (!currentKey) return false
  const current = new Uint8Array(currentKey)
  const expected = applicationKey(publicKey)
  return current.length === expected.length && current.every((byte, index) => byte === expected[index])
}

async function currentPushEndpoint() {
  try {
    const registration = await navigator.serviceWorker?.getRegistration()
    const subscription = await registration?.pushManager.getSubscription()
    return subscription?.endpoint ?? ''
  } catch {
    return ''
  }
}

async function associateCurrentDevicePush() {
  if (!('serviceWorker' in navigator) || !('PushManager' in window)
    || !('Notification' in window) || !window.isSecureContext
    || Notification.permission !== 'granted') return

  const configuration = await apiRequest<{ enabled: boolean; publicKey: string }>(
    '/api/notifications/configuration',
  )
  if (!configuration.enabled || !configuration.publicKey) return

  const registration = await navigator.serviceWorker.ready
  let subscription = await registration.pushManager.getSubscription()
  if (subscription && !subscriptionUsesApplicationKey(subscription, configuration.publicKey)) {
    const removed = await subscription.unsubscribe()
    if (!removed) return
    subscription = null
  }
  if (!subscription) {
    subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: applicationKey(configuration.publicKey),
    })
  }
  const keys = subscription.toJSON().keys
  if (!keys?.p256dh || !keys.auth) return
  await apiRequest<void>('/api/notifications/subscriptions', {
    method: 'POST',
    body: JSON.stringify({
      endpoint: subscription.endpoint,
      keys: { p256dh: keys.p256dh, auth: keys.auth },
    }),
  })
}

async function detachSignedOutAccountPush() {
  const endpoint = await currentPushEndpoint()
  if (!endpoint) return
  try {
    // Also cleans stale account associations left by logouts before this fix.
    await apiRequest<void>('/api/auth/logout', {
      method: 'POST',
      headers: { 'X-Push-Endpoint': endpoint },
    })
  } catch {
    // Cleanup is best-effort; authentication must remain signed out.
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    let active = true
    void apiRequest<CurrentUser>('/api/auth/me')
      .then((currentUser) => {
        if (active) {
          setUser(currentUser)
          void associateCurrentDevicePush().catch(() => {})
        }
      })
      .catch(async (cause: unknown) => {
        if (active) setUser(null)
        if (cause instanceof ApiError && cause.status === 401) {
          await detachSignedOutAccountPush()
        }
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
    // Browser permission is installation-specific. Ask once on the explicit
    // sign-in action, then associate this device automatically with accounts.
    if ('Notification' in window && Notification.permission === 'default'
      && window.localStorage.getItem(PUSH_PERMISSION_PROMPTED) !== 'true') {
      window.localStorage.setItem(PUSH_PERMISSION_PROMPTED, 'true')
      try {
        await Notification.requestPermission()
      } catch {
        // Sign-in continues if the user declines or the browser blocks the prompt.
      }
    }
    await getCsrfToken()
    const authenticatedUser = await apiRequest<CurrentUser>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password, rememberMe }),
    })
    await getCsrfToken()
    setUser(authenticatedUser)
    void associateCurrentDevicePush().catch(() => {})
    return authenticatedUser
  }, [])

  const logout = useCallback(async () => {
    const pushEndpoint = await currentPushEndpoint()
    await apiRequest<void>('/api/auth/logout', {
      method: 'POST',
      headers: pushEndpoint ? { 'X-Push-Endpoint': pushEndpoint } : undefined,
    })
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
