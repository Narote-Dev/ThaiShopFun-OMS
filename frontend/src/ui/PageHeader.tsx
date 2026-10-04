import type { ReactNode } from 'react'
import { cn } from './cn'

type Props = {
  title: ReactNode
  subtitle?: ReactNode
  actions?: ReactNode
  className?: string
}

export function PageHeader({ title, subtitle, actions, className }: Props) {
  return (
    <div className={cn('flex flex-wrap items-end justify-between gap-4', className)}>
      <div>
        <h1 className="text-[22px] font-semibold tracking-tight text-stone-900">{title}</h1>
        {subtitle ? <p className="mt-1 text-[13.5px] text-stone-500">{subtitle}</p> : null}
      </div>
      {actions ? <div className="flex flex-wrap gap-2">{actions}</div> : null}
    </div>
  )
}
