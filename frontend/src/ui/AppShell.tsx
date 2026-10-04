import {
  Bell,
  Boxes,
  ChevronRight,
  CircleHelp,
  ChevronsUpDown,
  LogOut,
  Search,
} from 'lucide-react'
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { listingsApi } from '../channel/listings/api'
import type { Me } from '../auth/AuthContext'
import { ordersApi } from '../orders/api'
import { ComingSoonButton } from './ComingSoon'
import { cn } from './cn'
import { breadcrumbParts, buildNavSections } from './nav'

type Props = {
  me: Me
  route: string
  userDisplayName: string | null
  onLogout: () => void
  children: ReactNode
}

function initials(name: string) {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  if (parts.length === 1) return parts[0].slice(0, 1).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}

const emptyFilters = {
  fulfillment_status: '',
  order_status: '',
  payment_status: '',
  hold_reason: '',
  channel: '',
  q: '',
  ordered_from: '',
  ordered_to: '',
}

export default function AppShell({ me, route, userDisplayName, onLogout, children }: Props) {
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const [counts, setCounts] = useState<{ orders: number | null; holds: number | null }>({
    orders: null,
    holds: null,
  })
  const [tsfConnected, setTsfConnected] = useState<boolean | null>(null)

  const sections = useMemo(() => buildNavSections(me), [me])
  const crumbs = breadcrumbParts(route.split('?')[0])
  const footerName = userDisplayName ?? 'User'
  const tierBadge =
    me.entitlement.status === 'ACTIVE'
      ? `${me.role} · ${me.tenant.membership_tier}`
      : `${me.tenant.membership_tier} · ${me.entitlement.status}`
  const showRoleSeparate = me.entitlement.status !== 'ACTIVE'

  useEffect(() => {
    // Change: close mobile drawer after hash navigation (Codex P2).
    // eslint-disable-next-line react-hooks/set-state-in-effect -- sync drawer to hash route
    setSidebarOpen(false)
  }, [route])

  useEffect(() => {
    let active = true
    void Promise.all([
      ordersApi.list(emptyFilters, 1, null),
      ordersApi.list({ ...emptyFilters, hold_reason: 'ANY' }, 1, null),
      listingsApi.listAccounts().catch(() => ({ items: [] })),
    ])
      .then(([all, held, accounts]) => {
        if (!active) return
        setCounts({ orders: all.total, holds: held.total })
        const tsf = accounts.items.filter((a) => a.channel === 'TSF')
        setTsfConnected(tsf.length > 0 && tsf.some((a) => a.status === 'CONNECTED'))
      })
      .catch(() => {
        if (active) {
          setCounts({ orders: null, holds: null })
          setTsfConnected(null)
        }
      })
    return () => {
      active = false
    }
  }, [route])

  function isActive(href: string) {
    const base = route.split('?')[0]
    if (href === '#/') return base === '#/'
    if (href === '#/orders')
      return base === '#/orders' || (base.startsWith('#/orders/') && !base.startsWith('#/orders/holds'))
    return base === href || base.startsWith(`${href}/`)
  }

  const tsfLabel =
    tsfConnected === null
      ? 'TSF…'
      : tsfConnected
        ? 'TSF เชื่อมต่อแล้ว'
        : 'TSF ยังไม่เชื่อมต่อ'

  return (
    <div className="flex min-h-screen bg-canvas font-sans text-stone-900 antialiased">
      {sidebarOpen ? (
        <button
          type="button"
          className="fixed inset-0 z-30 bg-stone-900/40 lg:hidden"
          aria-label="Close menu"
          onClick={() => setSidebarOpen(false)}
        />
      ) : null}
      <aside
        className={cn(
          'fixed inset-y-0 left-0 z-40 flex w-64 flex-col border-r border-stone-200 bg-white transition-transform lg:translate-x-0',
          sidebarOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0',
        )}
      >
        <div className="flex h-14 items-center gap-2.5 border-b border-stone-200 px-4">
          <div className="grid h-8 w-8 place-items-center rounded-lg bg-gradient-to-br from-brand-400 to-brand-600 text-white shadow-sm">
            <Boxes className="h-[18px] w-[18px]" strokeWidth={1.9} />
          </div>
          <div className="leading-tight">
            <div className="text-[13px] font-semibold tracking-tight">
              ThaiShopFun <span className="text-brand-600">OMS</span>
            </div>
            <div className="text-[11px] text-stone-500">Order Management</div>
          </div>
        </div>
        <div className="px-3 pt-3">
          <a
            href="#/"
            onClick={() => setSidebarOpen(false)}
            className="flex w-full items-center gap-2.5 rounded-lg border border-stone-200 bg-stone-50 px-2.5 py-2 text-left hover:bg-stone-100"
            aria-label={`${me.tenant.name} shop home`}
          >
            <div className="grid h-7 w-7 place-items-center rounded-md bg-brand-100 text-[12px] font-semibold text-brand-800">
              {initials(me.tenant.name)}
            </div>
            <div className="min-w-0 flex-1 leading-tight">
              <div className="truncate text-[13px] font-medium">{me.tenant.name}</div>
              <div className="flex items-center gap-1 text-[11px] text-stone-500">
                <span
                  className={cn(
                    'h-1.5 w-1.5 rounded-full',
                    tsfConnected ? 'bg-emerald-500' : tsfConnected === false ? 'bg-red-500' : 'bg-stone-300',
                  )}
                />
                {tsfLabel}
              </div>
            </div>
            <ChevronsUpDown className="h-4 w-4 text-stone-400" />
          </a>
        </div>
        <nav className="flex-1 overflow-y-auto px-3 py-3 text-[13.5px]" aria-label="Main">
          {sections.map((section) => (
            <div key={section.title}>
              <div className="mb-1 mt-3 px-2.5 text-[11px] font-medium uppercase tracking-wider text-stone-400 first:mt-0">
                {section.title}
              </div>
              {section.items.map((item) => {
                const active = isActive(item.href)
                const badge =
                  item.badgeKey === 'orders'
                    ? counts.orders
                    : item.badgeKey === 'holds'
                      ? counts.holds
                      : null
                const Icon = item.icon
                return (
                  <a
                    key={item.href}
                    href={item.href}
                    onClick={() => setSidebarOpen(false)}
                    aria-label={item.ariaLabel}
                    aria-current={active ? 'page' : undefined}
                    className={cn(
                      'flex items-center gap-2.5 rounded-md px-2.5 py-[7px]',
                      active
                        ? 'bg-brand-50 font-medium text-brand-800'
                        : 'text-stone-600 hover:bg-stone-100 hover:text-stone-900',
                    )}
                  >
                    <Icon className={cn('h-4 w-4', active ? 'text-brand-600' : 'text-stone-400')} strokeWidth={1.9} />
                    <span>{item.labelTh}</span>
                    {badge != null && badge > 0 ? (
                      <span
                        className={cn(
                          'ml-auto rounded-full px-1.5 py-px text-[11px] font-medium tabular-nums',
                          item.badgeKey === 'holds' ? 'bg-red-100 text-red-700' : 'bg-stone-100 text-stone-600',
                        )}
                      >
                        {badge}
                      </span>
                    ) : null}
                  </a>
                )
              })}
            </div>
          ))}
        </nav>
        <div className="border-t border-stone-200 p-3">
          <div className="flex items-center gap-2.5 rounded-lg px-1.5 py-1.5">
            <div className="grid h-8 w-8 place-items-center rounded-full bg-stone-800 text-[12px] font-semibold text-white">
              {initials(footerName)}
            </div>
            <div className="min-w-0 flex-1 leading-tight">
              <div className="truncate text-[13px] font-medium">{footerName}</div>
              <div className="mt-0.5 flex flex-wrap items-center gap-1.5 text-[10.5px]">
                {showRoleSeparate ? (
                  <span className="font-semibold text-stone-700">{me.role}</span>
                ) : null}
                <span className="inline-flex items-center rounded-full border border-brand-200 bg-brand-50 px-1.5 py-px font-semibold tracking-wide text-brand-800">
                  {tierBadge}
                </span>
              </div>
            </div>
            <button
              type="button"
              className="rounded-md p-1.5 text-stone-400 hover:bg-stone-100 hover:text-stone-700"
              title="ออกจากระบบ"
              aria-label="Log out"
              onClick={onLogout}
            >
              <LogOut className="h-4 w-4" />
            </button>
          </div>
        </div>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col lg:ml-64">
        <header className="sticky top-0 z-10 flex h-14 items-center gap-4 border-b border-stone-200 bg-white/85 px-4 backdrop-blur lg:px-6">
          <button
            type="button"
            className="rounded-lg border border-stone-200 px-2 py-1 text-[12px] lg:hidden"
            onClick={() => setSidebarOpen(true)}
          >
            Menu
          </button>
          <div className="flex min-w-0 items-center gap-1.5 text-[13px]">
            <span className="truncate text-stone-500">{me.tenant.name}</span>
            {crumbs.map((part) => (
              <span key={part} className="flex min-w-0 items-center gap-1.5">
                <ChevronRight className="h-3.5 w-3.5 shrink-0 text-stone-300" />
                <span className="truncate font-medium text-stone-900">{part}</span>
              </span>
            ))}
          </div>
          <div className="ml-auto flex items-center gap-2">
            <div className="relative hidden sm:block">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-stone-400" />
              <input
                disabled
                title="เร็วๆ นี้"
                className="h-9 w-56 cursor-not-allowed rounded-lg border border-stone-200 bg-stone-50 pl-8 pr-14 text-[13px] opacity-70 placeholder:text-stone-400 lg:w-80"
                placeholder="ค้นหาออเดอร์, SKU, สินค้า…"
                aria-label="ค้นหาทั่วระบบ"
              />
              <kbd className="absolute right-2 top-1/2 hidden -translate-y-1/2 rounded border border-stone-200 bg-white px-1.5 font-mono text-[10.5px] text-stone-500 lg:inline">
                ⌘K
              </kbd>
            </div>
            <ComingSoonButton variant="secondary" className="h-9 w-9 p-0" aria-label="Notifications">
              <Bell className="h-4 w-4" />
            </ComingSoonButton>
            <ComingSoonButton variant="secondary" className="h-9 w-9 p-0" aria-label="Help">
              <CircleHelp className="h-4 w-4" />
            </ComingSoonButton>
          </div>
        </header>
        {children}
      </div>
    </div>
  )
}
