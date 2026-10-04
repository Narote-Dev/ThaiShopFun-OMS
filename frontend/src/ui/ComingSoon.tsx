import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { Button } from './Button'
import { cn } from './cn'

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  children: ReactNode
  variant?: 'primary' | 'secondary' | 'ghost'
}

/** Disabled control with tooltip for placeholder shell actions. */
export function ComingSoonButton({ children, className, variant = 'secondary', ...props }: Props) {
  return (
    <Button
      type="button"
      variant={variant}
      disabled
      title="เร็วๆ นี้"
      className={cn(className)}
      {...props}
    >
      {children}
    </Button>
  )
}
