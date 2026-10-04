import type { ReactNode } from 'react'
import { cn } from './cn'

export type TabItem = {
  id: string
  label: ReactNode
  count?: number | null
  countClassName?: string
}

export function TabBar({
  items,
  activeId,
  onSelect,
  'aria-label': ariaLabel,
}: {
  items: TabItem[]
  activeId: string
  onSelect: (id: string) => void
  'aria-label'?: string
}) {
  return (
    <nav aria-label={ariaLabel} className="flex gap-6 border-b border-stone-200 px-5 pt-3">
      {items.map((tab) => {
        const active = tab.id === activeId
        return (
          <button
            key={tab.id}
            type="button"
            className={cn(
              'relative flex items-center gap-2 px-1 pb-3 pt-1 text-[13.5px]',
              active ? 'font-semibold text-stone-900' : 'text-stone-500 hover:text-stone-800',
            )}
            onClick={() => onSelect(tab.id)}
          >
            {tab.label}
            {tab.count != null ? (
              <span
                className={cn(
                  'rounded-full px-1.5 py-px text-[11px] font-medium tabular-nums',
                  active
                    ? 'bg-stone-900 text-white'
                    : tab.countClassName ?? 'bg-stone-100 text-stone-600',
                )}
              >
                {tab.count}
              </span>
            ) : null}
            {active ? (
              <span className="absolute inset-x-0 -bottom-px h-0.5 rounded-full bg-brand-600" />
            ) : null}
          </button>
        )
      })}
    </nav>
  )
}
