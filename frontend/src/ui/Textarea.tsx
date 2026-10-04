import type { TextareaHTMLAttributes } from 'react'
import { cn } from './cn'

export function Textarea({ className, ...props }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return (
    <textarea
      className={cn(
        'mt-1 block min-h-[80px] w-full rounded-lg border border-stone-200 bg-white px-3 py-2 text-[13px] text-stone-900 shadow-sm placeholder:text-stone-400 focus:border-brand-300 focus:outline-none focus:ring-4 focus:ring-brand-100',
        className,
      )}
      {...props}
    />
  )
}
