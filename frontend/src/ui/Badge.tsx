import { cva, type VariantProps } from 'class-variance-authority'
import type { HTMLAttributes } from 'react'
import { cn } from './cn'
const badgeVariants = cva(
  'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold tracking-wide uppercase',
  {
    variants: {
      variant: {
        success: 'bg-emerald-50 text-emerald-800 ring-1 ring-emerald-200/80',
        danger: 'bg-red-50 text-red-800 ring-1 ring-red-200/80',
        warning: 'bg-amber-50 text-amber-900 ring-1 ring-amber-200/80',
        muted: 'bg-stone-100 text-stone-600 ring-1 ring-stone-200/80',
        info: 'bg-sky-50 text-sky-800 ring-1 ring-sky-200/80',
        channel: 'bg-brand-600 text-white ring-1 ring-brand-700/30',
        violet: 'bg-violet-50 text-violet-800 ring-1 ring-violet-200/80',
        neutral: 'bg-stone-50 text-stone-700 ring-1 ring-stone-200/80',
      },
    },
    defaultVariants: { variant: 'neutral' },
  },
)

export function Badge({
  className,
  variant,
  strikethrough,
  ...props
}: HTMLAttributes<HTMLSpanElement> & VariantProps<typeof badgeVariants> & { strikethrough?: boolean }) {
  return (
    <span
      className={cn(badgeVariants({ variant }), strikethrough && 'line-through opacity-80', className)}
      {...props}
    />
  )
}

