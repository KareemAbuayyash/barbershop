import { createBrowserRouter } from 'react-router-dom'
import AppLayout from './AppLayout'
import AboutPage from '../features/about/pages/AboutPage'
import BookingPage from '../features/booking/pages/BookingPage'
import ContactPage from '../features/contact/pages/ContactPage'
import ForgotPasswordPage from '../features/auth/pages/ForgotPasswordPage'
import LoginPage from '../features/auth/pages/LoginPage'
import RegisterPage from '../features/auth/pages/RegisterPage'
import HomePage from '../features/home/pages/HomePage'
import MyBookingsPage from '../features/customer/pages/MyBookingsPage'
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
      { path: 'register', element: <RegisterPage /> },
      { path: 'forgot-password', element: <ForgotPasswordPage /> },
      { path: 'my-bookings', element: <MyBookingsPage /> },
      { path: 'about', element: <AboutPage /> },
      { path: 'contact', element: <ContactPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
