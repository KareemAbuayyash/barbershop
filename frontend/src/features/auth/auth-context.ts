import { createContext } from 'react'
import type { CurrentUser } from '../../lib/api'

export interface AuthContextValue {
  user: CurrentUser | null
  isLoading: boolean
  login: (username: string, password: string, rememberMe?: boolean) => Promise<CurrentUser>
  logout: () => Promise<void>
  updateUsername: (username: string) => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)
