import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import './styles/globals.css'
import { router } from './app/router'
import CustomFormValidation from './components/ui/CustomFormValidation'
import { AuthProvider } from './features/auth/AuthContext'

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    void navigator.serviceWorker.register('/sw.js')
      .catch((cause: unknown) => console.error('Unable to register the PWA service worker.', cause))
  })
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider>
      <CustomFormValidation />
      <RouterProvider router={router} />
    </AuthProvider>
  </StrictMode>,
)
