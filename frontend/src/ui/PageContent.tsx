import type { ReactNode } from 'react'
import { cn } from './cn'

export function PageContent({
  children,
  wide,
  className,
}: {
  children: ReactNode
  wide?: boolean
  className?: string
}) {
  return (
    <main
      className={cn(
        'mx-auto w-full flex-1 px-6 py-7 lg:px-8',
        wide ? 'max-w-[1400px]' : 'max-w-[1200px]',
        className,
      )}
    >
      {children}
    </main>
  )
}
