import type { InputHTMLAttributes } from 'react'
import { cn } from './cn'

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      className={cn(
        'mt-1 block h-9 w-full rounded-lg border border-stone-200 bg-white px-3 text-[13px] text-stone-900 shadow-sm placeholder:text-stone-400 focus:border-brand-300 focus:outline-none focus:ring-4 focus:ring-brand-100',
        className,
      )}
      {...props}
    />
  )
}
