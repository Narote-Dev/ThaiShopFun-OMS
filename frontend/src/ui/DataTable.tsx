import type { ReactNode } from 'react'
import { cn } from './cn'

export function DataTable({
  'aria-label': ariaLabel,
  children,
  className,
}: {
  'aria-label': string
  children: ReactNode
  className?: string
}) {
  return (
    <div className={cn('overflow-x-auto rounded-xl border border-stone-200 bg-white shadow-card', className)}>
      <table aria-label={ariaLabel} className="w-full border-collapse text-[13px]">
        {children}
      </table>
    </div>
  )
}

export function DataTableHead({ children }: { children: ReactNode }) {
  return (
    <thead className="border-b border-stone-100 bg-stone-50/80 text-left text-[12px] font-semibold uppercase tracking-wide text-stone-500">
      {children}
    </thead>
  )
}

export function DataTableRow({ children, className }: { children: ReactNode; className?: string }) {
  return <tr className={cn('border-b border-stone-100 hover:bg-stone-50/60', className)}>{children}</tr>
}

export function DataTableCell({
  children,
  className,
  mono,
}: {
  children: ReactNode
  className?: string
  mono?: boolean
}) {
  return (
    <td className={cn('px-4 py-2.5 align-middle text-stone-800', mono && 'font-mono text-[12.5px]', className)}>
      {children}
    </td>
  )
}

export function DataTableTh({ children, className }: { children?: ReactNode; className?: string }) {
  return <th className={cn('px-4 py-2.5 font-semibold', className)}>{children}</th>
}

export function TablePager({
  summary,
  children,
}: {
  summary: ReactNode
  children: ReactNode
}) {
  return (
    <div className="mt-4 flex flex-wrap items-center justify-between gap-3 text-[13px] text-stone-600">
      <div>{summary}</div>
      <div className="flex gap-2">{children}</div>
    </div>
  )
}

export function EmptyState({ title, description }: { title: string; description?: string }) {
  return (
    <div className="rounded-xl border border-dashed border-stone-200 bg-white px-6 py-12 text-center">
      <p className="text-[14px] font-medium text-stone-800">{title}</p>
      {description ? <p className="mt-1 text-[13px] text-stone-500">{description}</p> : null}
    </div>
  )
}
