import type { HTMLAttributes, ReactNode } from 'react'
import { cn } from './cn'

export function Card({ className, ...props }: HTMLAttributes<HTMLElement>) {
  return (
    <section
      className={cn('rounded-xl border border-stone-200 bg-white shadow-card', className)}
      {...props}
    />
  )
}

export function CardHeader({
  title,
  description,
  action,
  className,
}: {
  title: ReactNode
  description?: ReactNode
  action?: ReactNode
  className?: string
}) {
  return (
    <div className={cn('flex items-center justify-between border-b border-stone-100 px-5 py-3.5', className)}>
      <div>
        <h2 className="text-[14.5px] font-semibold text-stone-900">{title}</h2>
        {description ? <p className="text-[12px] text-stone-500">{description}</p> : null}
      </div>
      {action}
    </div>
  )
}

export function CardBody({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('p-5', className)} {...props} />
}

export function KpiCard({
  label,
  value,
  hint,
  icon,
  iconClassName,
}: {
  label: string
  value: ReactNode
  hint?: ReactNode
  icon: ReactNode
  iconClassName?: string
}) {
  return (
    <Card>
      <CardBody>
        <div className="flex items-center justify-between">
          <span className="text-[13px] font-medium text-stone-600">{label}</span>
          <span className={cn('grid h-8 w-8 place-items-center rounded-lg', iconClassName)}>{icon}</span>
        </div>
        <div className="mt-3 text-[30px] font-semibold leading-none tracking-tight tabular-nums">{value}</div>
        {hint ? <div className="mt-3 text-[12px] text-stone-500">{hint}</div> : null}
      </CardBody>
    </Card>
  )
}
