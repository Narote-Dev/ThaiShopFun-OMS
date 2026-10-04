import type { ReactNode } from 'react'
import { cn } from './cn'

export function FilterBar({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={cn('flex flex-wrap items-center gap-2 border-b border-stone-100 px-4 py-3', className)}>
      {children}
    </div>
  )
}

export function FilterChip({
  children,
  active,
  onClick,
}: {
  children: ReactNode
  active?: boolean
  onClick?: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'inline-flex h-9 items-center gap-1.5 whitespace-nowrap rounded-lg border px-3 text-[12.5px]',
        active
          ? 'border-brand-300 bg-brand-50 text-brand-900'
          : 'border-dashed border-stone-300 bg-white text-stone-700 hover:bg-stone-50',
      )}
    >
      {children}
    </button>
  )
}
