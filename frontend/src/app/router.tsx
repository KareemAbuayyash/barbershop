import { createBrowserRouter } from 'react-router-dom'
import AppLayout from './AppLayout'
import { Navigate } from 'react-router-dom'
import AboutPage from '../features/about/pages/AboutPage'
import BookingPage from '../features/booking/pages/BookingPage'
import ContactPage from '../features/contact/pages/ContactPage'
import LoginPage from '../features/auth/pages/LoginPage'
import OwnerPage from '../features/owner/pages/OwnerPage'
import OwnerRoute from '../features/owner/components/OwnerRoute'
import MyBookingsPage from '../features/customer/pages/MyBookingsPage'
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
      { path: 'login', element: <LoginPage /> },
      { path: 'register', element: <Navigate to="/login" replace /> },
      { path: 'owner', element: <OwnerRoute><OwnerPage /></OwnerRoute> },
      { path: 'my-bookings', element: <CustomerRoute><MyBookingsPage /></CustomerRoute> },
      { path: 'about', element: <AboutPage /> },
      { path: 'contact', element: <ContactPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
