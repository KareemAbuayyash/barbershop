import { useEffect, useRef, useState } from 'react'
import { ChevronDown, LogOut, Menu, Scissors, Settings, X } from 'lucide-react'
import { Link, NavLink, useNavigate } from 'react-router-dom'
import { navigation, site } from '../../config/site'
import Button from '../ui/Button'
import { useAuth } from '../../features/auth/useAuth'
import UnreadNotificationLink from '../../features/notifications/UnreadNotificationLink'
import GuestNotificationLink from '../../features/notifications/GuestNotificationLink'

function SiteHeader() {
  const { user, isLoading, logout } = useAuth()
  const navigate = useNavigate()
  const [isMenuOpen, setIsMenuOpen] = useState(false)
  const [isUserMenuOpen, setIsUserMenuOpen] = useState(false)
  const [logoutError, setLogoutError] = useState('')
  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const userMenuRef = useRef<HTMLDivElement>(null)
  const isOwner = user?.authorities.includes('ROLE_OWNER') ?? false
  const settingsPath = isOwner ? '/owner/settings' : '/my-settings'

  const closeMenu = () => {
    setIsMenuOpen(false)
    setIsUserMenuOpen(false)
    window.dispatchEvent(new Event('barbershop:close-admin-menu'))
  }

  const toggleMainMenu = () => {
    if (!isMenuOpen) window.dispatchEvent(new Event('barbershop:close-admin-menu'))
    setIsMenuOpen((open) => !open)
  }

  const handleLogout = async () => {
    setLogoutError('')
    try {
      await logout()
      closeMenu()
      navigate('/', { replace: true })
    } catch {
      setLogoutError('تعذر إنهاء الجلسة. حاول مرة أخرى.')
    }
  }

  useEffect(() => {
    if (!isMenuOpen) return
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        closeMenu()
        menuButtonRef.current?.focus()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [isMenuOpen])

  useEffect(() => {
    if (!isUserMenuOpen) return
    const closeOnOutsideClick = (event: PointerEvent) => {
      if (!userMenuRef.current?.contains(event.target as Node)) setIsUserMenuOpen(false)
    }
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setIsUserMenuOpen(false)
    }
    document.addEventListener('pointerdown', closeOnOutsideClick)
    document.addEventListener('keydown', closeOnEscape)
    return () => {
      document.removeEventListener('pointerdown', closeOnOutsideClick)
      document.removeEventListener('keydown', closeOnEscape)
    }
  }, [isUserMenuOpen])

  useEffect(() => {
    const closeMainMenu = () => {
      setIsMenuOpen(false)
      setIsUserMenuOpen(false)
    }
    window.addEventListener('barbershop:close-site-menu', closeMainMenu)
    return () => window.removeEventListener('barbershop:close-site-menu', closeMainMenu)
  }, [])

  const accountMenu = user && (
    <div className="header-account-menu" ref={userMenuRef}>
      <button className="header-account-trigger" type="button" aria-expanded={isUserMenuOpen}
        aria-haspopup="menu" onClick={() => setIsUserMenuOpen((open) => !open)}>
        <span>{user.username}</span><ChevronDown size={15} />
      </button>
      {isUserMenuOpen && <div className="header-account-dropdown" role="menu">
        <button className="header-logout" type="button" role="menuitem" onClick={() => void handleLogout()}>
          <LogOut size={16} aria-hidden="true" /> تسجيل الخروج
        </button>
        <Link to={settingsPath} role="menuitem" onClick={closeMenu}>
          <Settings size={16} aria-hidden="true" /> الإعدادات
        </Link>
      </div>}
    </div>
  )

  return (
    <header className="site-header">
      <div className="container header-inner">
        <Link className="brand" to="/" aria-label={`${site.brand} - الرئيسية`} onClick={closeMenu}>
          <span className="brand-mark" aria-hidden="true"><Scissors size={19} strokeWidth={1.6} /></span>
          <span className="brand-name">{site.brand}</span>
        </Link>

        <button ref={menuButtonRef} className="menu-toggle" type="button"
          aria-label={isMenuOpen ? 'إغلاق القائمة' : 'فتح القائمة'} aria-expanded={isMenuOpen}
          aria-controls="primary-navigation" onClick={toggleMainMenu}>
          {isMenuOpen ? <X size={22} /> : <Menu size={22} />}
        </button>

        <nav className={`primary-navigation${isMenuOpen ? ' primary-navigation--open' : ''}`}
          id="primary-navigation" aria-label="القائمة الرئيسية">
          {navigation.map((item) => (
            <NavLink to={item.href} key={item.href} end={item.href === '/'} onClick={closeMenu}>
              {item.label}
            </NavLink>
          ))}
          <GuestNotificationLink onClick={closeMenu} />
          {!isLoading && (isOwner ? (
            <>
              <NavLink className="login-link" to="/owner" onClick={closeMenu}>لوحة المالك</NavLink>
              <UnreadNotificationLink to="/owner/notifications" label="الإشعارات" />
              {accountMenu}
            </>
          ) : user?.authorities.includes('ROLE_CUSTOMER') ? (
            <>
              <NavLink className="login-link" to="/my-bookings" onClick={closeMenu}>حجوزاتي</NavLink>
              <UnreadNotificationLink to="/my-notifications" label="الإشعارات" />
              {accountMenu}
            </>
          ) : <NavLink className="login-link" to="/login" onClick={closeMenu}>تسجيل الدخول</NavLink>)}
          <Button className="header-cta" to="/booking" onClick={closeMenu}>احجز موعدك</Button>
        </nav>
        {logoutError && <p className="header-error" role="alert">{logoutError}</p>}
      </div>
    </header>
  )
}

export default SiteHeader
