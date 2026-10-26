import type { ReactNode } from 'react'
import { Link, type LinkProps } from 'react-router-dom'

interface ButtonProps extends LinkProps {
  children: ReactNode
  variant?: 'primary' | 'secondary'
}

function Button({
  children,
  variant = 'primary',
  className = '',
  to,
  ...props
}: ButtonProps) {
  return (
    <Link className={`button button--${variant} ${className}`} to={to} {...props}>
      {children}
    </Link>
  )
}

export default Button
