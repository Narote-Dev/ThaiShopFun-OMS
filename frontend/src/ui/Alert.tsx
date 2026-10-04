import type { ReactNode } from 'react'
import { cn } from './cn'

export function AlertBanner({
  variant = 'warning',
  title,
  children,
  actions,
  footer,
  className,
}: {
  variant?: 'warning' | 'danger' | 'info'
  title: ReactNode
  children?: ReactNode
  actions?: ReactNode
  footer?: ReactNode
  className?: string
}) {
  const styles = {
    warning: 'border-amber-200 bg-amber-50 text-amber-950',
    danger: 'border-red-200 bg-red-50 text-red-950',
    info: 'border-sky-200 bg-sky-50 text-sky-950',
  }[variant]
  return (
    <div className={cn('rounded-xl border px-4 py-3.5', styles, className)}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1 space-y-1">
          <div className="text-[13.5px] font-semibold">{title}</div>
          {children ? <div className="text-[13px] opacity-90">{children}</div> : null}
        </div>
        {actions ? <div className="flex shrink-0 flex-wrap gap-2">{actions}</div> : null}
      </div>
      {footer ? <div className="mt-2 border-t border-black/5 pt-2 text-[12px]">{footer}</div> : null}
    </div>
  )
}
