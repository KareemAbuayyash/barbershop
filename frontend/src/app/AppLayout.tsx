import { Outlet } from 'react-router-dom'
import SiteFooter from '../components/layout/SiteFooter'
import SiteHeader from '../components/layout/SiteHeader'

function AppLayout() {
  return (
    <>
      <a className="skip-link" href="#main-content">انتقل إلى المحتوى</a>
      <SiteHeader />
      <main id="main-content">
        <Outlet />
      </main>
      <SiteFooter />
    </>
  )
}

export default AppLayout
