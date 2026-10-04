import type { SelectHTMLAttributes } from 'react'
import { cn } from './cn'

export function Select({ className, ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select
      className={cn(
        'mt-1 block h-9 w-full rounded-lg border border-stone-200 bg-white px-3 text-[13px] text-stone-900 shadow-sm focus:border-brand-300 focus:outline-none focus:ring-4 focus:ring-brand-100',
        className,
      )}
      {...props}
    />
  )
}
