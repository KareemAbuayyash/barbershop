import { Outlet } from 'react-router-dom'
import { useLocation } from 'react-router-dom'
import SiteFooter from '../components/layout/SiteFooter'
import SiteHeader from '../components/layout/SiteHeader'

function AppLayout() {
  const { pathname } = useLocation()
  if (pathname.startsWith('/owner')) {
    return (
      <>
        <a className="skip-link" href="#main-content">انتقل إلى المحتوى</a>
        <SiteHeader />
        <main id="main-content"><Outlet /></main>
      </>
    )
  }

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">انتقل إلى المحتوى</a>
      <SiteHeader />
      <main id="main-content">
        <Outlet />
      </main>
      <SiteFooter />
    </div>
  )
}

export default AppLayout
