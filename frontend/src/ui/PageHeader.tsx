import type { ReactNode } from 'react'
import { cn } from './cn'

type Props = {
  title: ReactNode
  /** Overrides the accessible name when visible title differs (e.g. Thai + English for tests). */
  titleAriaLabel?: string
  subtitle?: ReactNode
  actions?: ReactNode
  className?: string
}

export function PageHeader({ title, titleAriaLabel, subtitle, actions, className }: Props) {
  return (
    <div className={cn('flex flex-wrap items-end justify-between gap-4', className)}>
      <div>
        <h1
          aria-label={titleAriaLabel}
          className="text-[22px] font-semibold tracking-tight text-stone-900"
        >
          {title}
        </h1>
        {subtitle ? <p className="mt-1 text-[13.5px] text-stone-500">{subtitle}</p> : null}
      </div>
      {actions ? <div className="flex flex-wrap gap-2">{actions}</div> : null}
    </div>
  )
}
