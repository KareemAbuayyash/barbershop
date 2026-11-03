import { useEffect, useState } from 'react'
import {
  Bell,
  CalendarDays,
  ChartNoAxesCombined,
  ChevronRight,
  CircleDollarSign,
  Gauge,
  Menu,
  Package,
  Scissors,
  Settings,
  ShoppingBasket,
  Users,
  X,
} from 'lucide-react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../../auth/useAuth'

const navigationSections = [
  {
    title: 'الرئيسية',
    links: [
      { label: 'لوحة المعلومات', to: '/owner', icon: Gauge, end: true },
      { label: 'الحجوزات', to: '/owner/bookings', icon: CalendarDays },
      { label: 'العملاء', to: '/owner/customers', icon: Users },
      { label: 'الخدمات', to: '/owner/services', icon: Scissors },
    ],
  },
  {
    title: 'الإدارة المالية',
    links: [
      { label: 'الحسابات والديون', to: '/owner/accounts', icon: CircleDollarSign },
      { label: 'المصروفات', to: '/owner/expenses', icon: ShoppingBasket },
      { label: 'الأرباح والتقارير', to: '/owner/reports', icon: ChartNoAxesCombined },
    ],
  },
  {
    title: 'إدارة المحل',
    links: [
      { label: 'المخزون', to: '/owner/inventory', icon: Package },
      { label: 'الإشعارات', to: '/owner/notifications', icon: Bell },
      { label: 'إعدادات المحل', to: '/owner/settings', icon: Settings },
    ],
  },
]

const pageTitles: Record<string, string> = {
  '/owner': 'لوحة المعلومات',
  '/owner/bookings': 'إدارة الحجوزات',
  '/owner/customers': 'العملاء',
  '/owner/services': 'الخدمات',
  '/owner/accounts': 'الحسابات والديون',
  '/owner/expenses': 'المصروفات',
  '/owner/reports': 'الأرباح والتقارير',
  '/owner/inventory': 'المخزون',
  '/owner/notifications': 'الإشعارات',
  '/owner/settings': 'إعدادات المحل',
  '/owner/settings/notifications': 'إعدادات الإشعارات',
}

function AdminLayout() {
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const { user } = useAuth()
  const location = useLocation()
  useEffect(() => {
    setSidebarOpen(false)
  }, [location.pathname])

  useEffect(() => {
    const closeSidebar = () => setSidebarOpen(false)
    window.addEventListener('barbershop:close-admin-menu', closeSidebar)
    return () => window.removeEventListener('barbershop:close-admin-menu', closeSidebar)
  }, [])
  const title = location.pathname === '/owner/bookings' && location.search.includes('view=queue')
    ? 'قائمة يومية'
    : pageTitles[location.pathname] ?? 'إدارة المحل'

  return (
    <div className="admin-shell" dir="rtl">
      {sidebarOpen && (
        <button
          className="admin-sidebar-scrim"
          type="button"
          aria-label="إغلاق القائمة الجانبية"
          onClick={() => setSidebarOpen(false)}
        />
      )}
      <aside className={`admin-sidebar${sidebarOpen ? ' admin-sidebar--open' : ''}`} aria-label="قائمة الإدارة">
        <div className="admin-sidebar-top">
          <NavLink className="admin-brand" to="/owner" onClick={() => setSidebarOpen(false)}>
            <span className="admin-brand-mark"><Scissors size={21} /></span>
            <span><strong>لوحة المالك</strong><small>إدارة المحل</small></span>
          </NavLink>
          <button
            type="button"
            className="admin-sidebar-close"
            aria-label="إغلاق القائمة"
            onClick={(event) => {
              event.preventDefault()
              setSidebarOpen(false)
            }}
          ><X size={20} /></button>
        </div>
        <nav className="admin-nav">
          {navigationSections.map((section) => (
            <section className="admin-nav-section" key={section.title}>
              <h2>{section.title}</h2>
              {section.links.map(({ label, to, icon: Icon, end }) => (
                <NavLink
                  className={({ isActive }) => `admin-nav-link${isActive ? ' admin-nav-link--active' : ''}`}
                  to={to}
                  end={end}
                  key={to}
                  onClick={() => setSidebarOpen(false)}
                >
                  <Icon size={18} strokeWidth={1.8} />
                  <span>{label}</span>
                  {to === location.pathname && <ChevronRight className="admin-nav-current" size={15} />}
                </NavLink>
              ))}
            </section>
          ))}
        </nav>
        <div className="admin-sidebar-footer">
          <span className="admin-owner-avatar">{user?.username.slice(0, 1).toUpperCase() ?? 'م'}</span>
          <span><strong>{user?.username ?? 'المالك'}</strong><small>مالك المحل</small></span>
        </div>
      </aside>

      <div className="admin-main">
        <header className="admin-header">
          <button
            type="button"
            className="admin-menu-toggle"
            aria-label="فتح القائمة الجانبية"
            aria-expanded={sidebarOpen}
            onClick={() => {
              window.dispatchEvent(new Event('barbershop:close-site-menu'))
              setSidebarOpen(true)
            }}
          ><Menu size={21} /></button>
          <div className="admin-header-title">
            <span>إدارة المحل</span>
            <h1>{title}</h1>
          </div>
        </header>
        <main className="admin-content"><Outlet /></main>
      </div>
    </div>
  )
}

export default AdminLayout
