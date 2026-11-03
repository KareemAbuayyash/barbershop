import { createBrowserRouter } from 'react-router-dom'
import AppLayout from './AppLayout'
import { Navigate } from 'react-router-dom'
import AboutPage from '../features/about/pages/AboutPage'
import BookingPage from '../features/booking/pages/BookingPage'
import ContactPage from '../features/contact/pages/ContactPage'
import LoginPage from '../features/auth/pages/LoginPage'
import OwnerPage from '../features/owner/pages/OwnerPage'
import OwnerRoute from '../features/owner/components/OwnerRoute'
import AdminLayout from '../features/owner/layout/AdminLayout'
import AdminBookingsPage from '../features/owner/pages/AdminBookingsPage'
import AdminCustomersPage from '../features/owner/pages/AdminCustomersPage'
import AdminServicesPage from '../features/owner/pages/AdminServicesPage'
import AdminFinancePage from '../features/owner/pages/AdminFinancePage'
import AdminInventoryPage from '../features/owner/pages/AdminInventoryPage'
import AdminEmployeesPage from '../features/owner/pages/AdminEmployeesPage'
import NotificationCenterPage from '../features/notifications/NotificationCenterPage'
import CustomerNotificationsPage from '../features/notifications/CustomerNotificationsPage'
import NotificationSettingsPage from '../features/notifications/NotificationSettingsPage'
import GuestNotificationPage from '../features/notifications/GuestNotificationPage'
import AdminSettingsPage from '../features/owner/pages/AdminSettingsPage'
import MyBookingsPage from '../features/customer/pages/MyBookingsPage'
import CustomerSettingsPage from '../features/customer/pages/CustomerSettingsPage'
import CustomerRoute from '../features/customer/components/CustomerRoute'
import HomePage from '../features/home/pages/HomePage'
import NotFoundPage from '../features/home/pages/NotFoundPage'
import ServicesPage from '../features/services/pages/ServicesPage'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'services', element: <ServicesPage /> },
      { path: 'booking', element: <BookingPage /> },
      { path: 'guest-notifications', element: <GuestNotificationPage /> },
      { path: 'guest-notifications/:token', element: <GuestNotificationPage /> },
      { path: 'login', element: <LoginPage /> },
      { path: 'register', element: <Navigate to="/login" replace /> },
      {
        path: 'owner',
        element: <OwnerRoute><AdminLayout /></OwnerRoute>,
        children: [
          { index: true, element: <OwnerPage /> },
          { path: 'bookings', element: <AdminBookingsPage /> },
          { path: 'queue', element: <AdminBookingsPage queueOnly /> },
          { path: 'customers', element: <AdminCustomersPage /> },
          { path: 'services', element: <AdminServicesPage /> },
          { path: 'accounts', element: <AdminFinancePage section="accounts" /> },
          { path: 'payments', element: <AdminFinancePage section="payments" /> },
          { path: 'debts', element: <Navigate to="/owner/accounts" replace /> },
          { path: 'expenses', element: <AdminFinancePage section="expenses" /> },
          { path: 'reports', element: <AdminFinancePage section="reports" /> },
          { path: 'inventory', element: <AdminInventoryPage /> },
          { path: 'employees', element: <AdminEmployeesPage /> },
          { path: 'notifications', element: <NotificationCenterPage /> },
          { path: 'settings', element: <AdminSettingsPage /> },
          { path: 'settings/notifications', element: <NotificationSettingsPage /> },
        ],
      },
      { path: 'my-bookings', element: <CustomerRoute><MyBookingsPage /></CustomerRoute> },
      { path: 'my-notifications', element: <CustomerRoute><CustomerNotificationsPage /></CustomerRoute> },
      { path: 'my-settings', element: <CustomerRoute><CustomerSettingsPage /></CustomerRoute> },
      { path: 'my-settings/notifications', element: <CustomerRoute><NotificationSettingsPage /></CustomerRoute> },
      { path: 'about', element: <AboutPage /> },
      { path: 'contact', element: <ContactPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
