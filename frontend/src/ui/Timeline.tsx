import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from './cn'

export type TimelineItem = {
  id: string
  icon: LucideIcon
  iconClassName?: string
  title: ReactNode
  time?: ReactNode
  description?: ReactNode
  dashed?: boolean
}

export function Timeline({ items, 'aria-label': ariaLabel }: { items: TimelineItem[]; 'aria-label'?: string }) {
  return (
    <ol aria-label={ariaLabel} className="px-5 py-4">
      {items.map((item, index) => {
        const Icon = item.icon
        const last = index === items.length - 1
        return (
          <li key={item.id} className={cn('relative flex gap-3', !last && 'pb-6')}>
            {!last ? (
              <span
                className={cn(
                  'absolute left-[15px] top-9 -bottom-1 w-px',
                  item.dashed ? 'border-l border-dashed border-stone-300' : 'bg-stone-200',
                )}
              />
            ) : null}
            <span
              className={cn(
                'relative z-[1] grid h-8 w-8 shrink-0 place-items-center rounded-full ring-1',
                item.iconClassName ?? 'bg-white text-stone-400 ring-stone-300',
              )}
            >
              <Icon className="h-[15px] w-[15px]" strokeWidth={1.9} />
            </span>
            <div className="min-w-0 flex-1 pt-1">
              <div className="flex items-start justify-between gap-3">
                <div className="text-[13px] font-medium text-stone-900">{item.title}</div>
                {item.time ? (
                  <div className="whitespace-nowrap text-[11.5px] text-stone-400 tabular-nums">{item.time}</div>
                ) : null}
              </div>
              {item.description ? (
                <div className="mt-0.5 text-[12px] text-stone-500">{item.description}</div>
              ) : null}
            </div>
          </li>
        )
      })}
    </ol>
  )
}
