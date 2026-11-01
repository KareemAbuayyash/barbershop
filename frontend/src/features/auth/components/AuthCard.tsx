import type { ReactNode } from 'react'

interface AuthCardProps {
  eyebrow: string
  title: string
  description?: string
  children: ReactNode
}

function AuthCard({ eyebrow, title, description, children }: AuthCardProps) {
  return (
    <section className="auth-card" aria-labelledby="auth-title">
      <span className="eyebrow">{eyebrow}</span>
      <h1 id="auth-title">{title}</h1>
      {description && <p className="auth-description">{description}</p>}
      {children}
    </section>
  )
}

export default AuthCard
