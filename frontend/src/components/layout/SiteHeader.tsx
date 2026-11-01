import { useEffect, useRef, useState } from 'react'
import { LogOut, Menu, Scissors, X } from 'lucide-react'
import { Link, NavLink, useNavigate } from 'react-router-dom'
import { navigation, site } from '../../config/site'
import Button from '../ui/Button'
import { useAuth } from '../../features/auth/useAuth'

function SiteHeader() {
  const { user, isLoading, logout } = useAuth()
  const navigate = useNavigate()
  const [isMenuOpen, setIsMenuOpen] = useState(false)
  const [logoutError, setLogoutError] = useState('')
  const menuButtonRef = useRef<HTMLButtonElement>(null)

  const closeMenu = () => setIsMenuOpen(false)
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

  return (
    <header className="site-header">
      <div className="container header-inner">
        <Link className="brand" to="/" aria-label={`${site.brand} - الرئيسية`} onClick={closeMenu}>
          <span className="brand-mark" aria-hidden="true">
            <Scissors size={19} strokeWidth={1.6} />
          </span>
          <span className="brand-name">{site.brand}</span>
        </Link>

        <button
          ref={menuButtonRef}
          className="menu-toggle"
          type="button"
          aria-label={isMenuOpen ? 'إغلاق القائمة' : 'فتح القائمة'}
          aria-expanded={isMenuOpen}
          aria-controls="primary-navigation"
          onClick={() => setIsMenuOpen((open) => !open)}
        >
          {isMenuOpen ? <X size={22} /> : <Menu size={22} />}
        </button>

        <nav
          className={`primary-navigation${isMenuOpen ? ' primary-navigation--open' : ''}`}
          id="primary-navigation"
          aria-label="القائمة الرئيسية"
        >
          {navigation.map((item) => (
            <NavLink
              to={item.href}
              key={item.href}
              end={item.href === '/'}
              onClick={closeMenu}
            >
              {item.label}
            </NavLink>
          ))}
          {!isLoading && (user?.authorities.includes('ROLE_OWNER') ? (
            <>
              <NavLink className="login-link" to="/owner" onClick={closeMenu}>لوحة المالك</NavLink>
              <button className="header-logout" type="button" onClick={() => void handleLogout()}>
                <LogOut size={15} aria-hidden="true" /> تسجيل الخروج
              </button>
            </>
          ) : user?.authorities.includes('ROLE_CUSTOMER') ? (
            <>
              <NavLink className="login-link" to="/my-bookings" onClick={closeMenu}>حجوزاتي</NavLink>
              <button className="header-logout" type="button" onClick={() => void handleLogout()}>
                <LogOut size={15} aria-hidden="true" /> تسجيل الخروج
              </button>
            </>
          ) : (
            <>
              <NavLink className="login-link" to="/login" onClick={closeMenu}>تسجيل الدخول</NavLink>
              <NavLink className="login-link" to="/register" onClick={closeMenu}>إنشاء حساب</NavLink>
            </>
          ))}
          <Button className="header-cta" to="/booking" onClick={closeMenu}>
            احجز موعدك
          </Button>
        </nav>
        {logoutError && <p className="header-error" role="alert">{logoutError}</p>}
      </div>
    </header>
  )
}

export default SiteHeader
