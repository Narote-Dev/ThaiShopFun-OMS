import { Download, Printer, RefreshCw } from 'lucide-react'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { Checkbox } from '../ui/Checkbox'
import { ComingSoonButton } from '../ui/ComingSoon'
import {
  DataTable,
  DataTableCell,
  DataTableHead,
  DataTableRow,
  DataTableTh,
  TablePager,
} from '../ui/DataTable'
import { AlertBanner } from '../ui/Alert'
import { HoldReasonBadge } from '../ui/HoldReasonBadge'
import { Input } from '../ui/Input'
import { Label } from '../ui/Label'
import { OrderDisplayBadge } from '../ui/OrderDisplayBadge'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'
import { Select } from '../ui/Select'
import { StatusBadge } from '../ui/StatusBadge'
import { TabBar } from '../ui/Tabs'
import { bangkokTodayIso, formatBangkokDateTime, formatMoney } from '../ui/format'
import {
  ORDER_PAGE_SIZE,
  ordersApi,
  ordersMessage,
  type OrderFilters,
  type OrderListItem,
  type OrdersPage,
} from './api'
import { fetchOrderTotal } from './listOrders'
import {
  ORDER_TAB_LABELS,
  emptyOrderFilters,
  filtersForTab,
  tabFromFilters,
  type OrdersTabId,
} from './orderViews'

const TAB_IDS: OrdersTabId[] = ['all', 'ready', 'hold', 'unshipped', 'shipped', 'cancelled']

function filtersFromHash(): OrderFilters {
  const hash = window.location.hash
  const query = hash.includes('?') ? hash.slice(hash.indexOf('?') + 1) : ''
  if (!query) return emptyOrderFilters()
  const params = new URLSearchParams(query)
  return {
    fulfillment_status: params.get('fulfillment_status') ?? '',
    order_status: params.get('order_status') ?? '',
    payment_status: params.get('payment_status') ?? '',
    hold_reason: params.get('hold_reason') ?? '',
    channel: params.get('channel') ?? '',
    q: params.get('q') ?? '',
    ordered_from: params.get('ordered_from') ?? '',
    ordered_to: params.get('ordered_to') ?? '',
  }
}

function cursorFromHash(): string | null {
  const query = window.location.hash.includes('?') ? window.location.hash.split('?')[1] : ''
  const value = new URLSearchParams(query).get('cursor')
  return value && value.length > 0 ? value : null
}

function writeHash(filters: OrderFilters, cursor: string | null) {
  const params = new URLSearchParams()
  if (filters.fulfillment_status) params.set('fulfillment_status', filters.fulfillment_status)
  if (filters.order_status) params.set('order_status', filters.order_status)
  if (filters.payment_status) params.set('payment_status', filters.payment_status)
  if (filters.hold_reason) params.set('hold_reason', filters.hold_reason)
  if (filters.channel) params.set('channel', filters.channel)
  if (filters.q) params.set('q', filters.q)
  if (filters.ordered_from) params.set('ordered_from', filters.ordered_from)
  if (filters.ordered_to) params.set('ordered_to', filters.ordered_to)
  if (cursor) params.set('cursor', cursor)
  const qs = params.toString()
  window.location.hash = qs ? `#/orders?${qs}` : '#/orders'
}

export default function OrdersListPage() {
  const [filters, setFilters] = useState<OrderFilters>(filtersFromHash)
  const [applied, setApplied] = useState<OrderFilters>(filtersFromHash)
  const [cursor, setCursor] = useState<string | null>(cursorFromHash)
  const [backStack, setBackStack] = useState<string[]>([])
  const [page, setPage] = useState<OrdersPage<OrderListItem> | null>(null)
  const [error, setError] = useState('')
  const [tabCounts, setTabCounts] = useState<Partial<Record<OrdersTabId, number>>>({})
  const [holdTotal, setHoldTotal] = useState(0)
  const [selected, setSelected] = useState<Set<string>>(new Set())

  const activeTab = tabFromFilters(applied)
  const today = bangkokTodayIso()

  useEffect(() => {
    const syncFromHash = () => {
      const next = filtersFromHash()
      const nextCursor = cursorFromHash()
      setFilters(next)
      setApplied((prev) => {
        const filtersChanged =
          prev.fulfillment_status !== next.fulfillment_status ||
          prev.order_status !== next.order_status ||
          prev.payment_status !== next.payment_status ||
          prev.hold_reason !== next.hold_reason ||
          prev.channel !== next.channel ||
          prev.q !== next.q ||
          prev.ordered_from !== next.ordered_from ||
          prev.ordered_to !== next.ordered_to
        if (filtersChanged) {
          setBackStack([])
          setSelected(new Set())
        }
        return next
      })
      setCursor(nextCursor)
    }
    window.addEventListener('hashchange', syncFromHash)
    return () => window.removeEventListener('hashchange', syncFromHash)
  }, [])

  useEffect(() => {
    let active = true
    ordersApi
      .list(applied, ORDER_PAGE_SIZE, cursor)
      .then((next) => {
        if (!active) return
        setPage(next)
        setError('')
      })
      .catch((err: unknown) => {
        if (active) setError(ordersMessage(err, 'Could not load orders'))
      })
    return () => {
      active = false
    }
  }, [applied, cursor])

  useEffect(() => {
    let active = true
    void Promise.all([
      ...TAB_IDS.map(async (id) => [id, await fetchOrderTotal(filtersForTab(id, today))] as const),
      fetchOrderTotal({ ...emptyOrderFilters(today), hold_reason: 'ANY' }),
    ])
      .then((results) => {
        if (!active) return
        const counts: Partial<Record<OrdersTabId, number>> = {}
        for (const [id, total] of results.slice(0, TAB_IDS.length) as [OrdersTabId, number][]) {
          counts[id] = total
        }
        setTabCounts(counts)
        setHoldTotal(results[results.length - 1] as number)
      })
      .catch(() => {
        if (active) {
          setTabCounts({})
          setHoldTotal(0)
        }
      })
    return () => {
      active = false
    }
  }, [today])

  const tabItems = useMemo(
    () =>
      TAB_IDS.map((id) => ({
        id,
        label: ORDER_TAB_LABELS[id],
        count: tabCounts[id] ?? null,
        countClassName: id === 'hold' && (tabCounts.hold ?? 0) > 0 ? 'bg-red-100 text-red-700' : undefined,
      })),
    [tabCounts],
  )

  function selectTab(tabId: OrdersTabId) {
    const tabFilters = filtersForTab(tabId, today)
    const next: OrderFilters = {
      ...tabFilters,
      q: filters.q,
      channel: filters.channel,
      payment_status: filters.payment_status,
    }
    setFilters(next)
    setApplied(next)
    setCursor(null)
    setBackStack([])
    setSelected(new Set())
    writeHash(next, null)
  }

  function apply(event: FormEvent) {
    event.preventDefault()
    setApplied(filters)
    setCursor(null)
    setBackStack([])
    setSelected(new Set())
    writeHash(filters, null)
  }

  const rows = page?.items ?? []
  const allSelected = rows.length > 0 && rows.every((row) => selected.has(row.id))

  function toggleAll(checked: boolean) {
    if (!checked) {
      setSelected(new Set())
      return
    }
    setSelected(new Set(rows.map((row) => row.id)))
  }

  const total = page?.total ?? 0

  return (
    <PageContent wide>
      <PageHeader
        title={
          <>
            <span aria-hidden="true">ออเดอร์</span>
            <span className="sr-only">Orders</span>
          </>
        }
        subtitle="จัดการออเดอร์จากทุกช่องทาง"
        actions={
          <>
            <ComingSoonButton>
              <Download className="h-4 w-4" />
              Export
            </ComingSoonButton>
            <ComingSoonButton>
              <RefreshCw className="h-4 w-4" />
              Sync
            </ComingSoonButton>
            <ComingSoonButton>
              <Printer className="h-4 w-4" />
              Print
            </ComingSoonButton>
          </>
        }
      />

      {holdTotal > 0 ? (
        <AlertBanner
          variant="warning"
          className="mt-5"
          title="มีออเดอร์ค้างที่ต้องแก้ไข"
          actions={
            <Button type="button" variant="secondary" asChild>
              <a href="#/orders/holds">ดูคิว Hold →</a>
            </Button>
          }
        >
          {holdTotal} ออเดอร์รอการดำเนินการ
        </AlertBanner>
      ) : null}

      <Card className="mt-5 overflow-hidden">
        <TabBar
          aria-label="Fulfillment tabs"
          items={tabItems}
          activeId={activeTab}
          onSelect={(id) => selectTab(id as OrdersTabId)}
        />

        <form
          className="grid gap-4 border-b border-stone-100 p-4 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-5"
          onSubmit={apply}
          aria-label="Filter orders"
        >
          <div>
            <Label htmlFor="orders-search">Search</Label>
            <Input
              id="orders-search"
              value={filters.q}
              onChange={(e) => setFilters({ ...filters, q: e.target.value })}
            />
          </div>
          <div>
            <Label htmlFor="orders-hold">Hold</Label>
            <Select
              id="orders-hold"
              aria-label="Hold filter"
              value={filters.hold_reason}
              onChange={(e) => setFilters({ ...filters, hold_reason: e.target.value })}
            >
              <option value="">Any</option>
              <option value="NONE">None</option>
              <option value="ANY">On hold</option>
              <option value="SKU_NOT_MAPPED">SKU not mapped</option>
              <option value="OUT_OF_STOCK">Out of stock</option>
              <option value="ADDRESS_PROBLEM">Address problem</option>
              <option value="PAYMENT_MISMATCH">Payment mismatch</option>
              <option value="CHANNEL_CANCEL_PENDING">Channel cancel pending</option>
              <option value="MANUAL">Manual</option>
            </Select>
          </div>
          <div>
            <Label htmlFor="orders-order-status">Order status</Label>
            <Select
              id="orders-order-status"
              value={filters.order_status}
              onChange={(e) => setFilters({ ...filters, order_status: e.target.value })}
            >
              <option value="">Any</option>
              <option value="ACTIVE">Active</option>
              <option value="CANCELLED">Cancelled</option>
              <option value="COMPLETED">Completed</option>
            </Select>
          </div>
          <div>
            <Label htmlFor="orders-payment-status">Payment status</Label>
            <Select
              id="orders-payment-status"
              value={filters.payment_status}
              onChange={(e) => setFilters({ ...filters, payment_status: e.target.value })}
            >
              <option value="">Any</option>
              <option value="PENDING">Pending</option>
              <option value="PAID">Paid</option>
              <option value="COD_PENDING">COD pending</option>
              <option value="PARTIALLY_REFUNDED">Partially refunded</option>
              <option value="REFUNDED">Refunded</option>
            </Select>
          </div>
          <div>
            <Label htmlFor="orders-channel">Channel</Label>
            <Select
              id="orders-channel"
              value={filters.channel}
              onChange={(e) => setFilters({ ...filters, channel: e.target.value })}
            >
              <option value="">Any</option>
              <option value="TSF">TSF</option>
              <option value="SHOPEE">Shopee</option>
              <option value="LAZADA">Lazada</option>
              <option value="TIKTOK">TikTok</option>
            </Select>
          </div>
          <div>
            <Label htmlFor="orders-from">Ordered from</Label>
            <Input
              id="orders-from"
              type="date"
              value={filters.ordered_from}
              onChange={(e) => setFilters({ ...filters, ordered_from: e.target.value })}
            />
          </div>
          <div>
            <Label htmlFor="orders-to">Ordered to</Label>
            <Input
              id="orders-to"
              type="date"
              value={filters.ordered_to}
              onChange={(e) => setFilters({ ...filters, ordered_to: e.target.value })}
            />
          </div>
          <div className="flex items-end">
            <Button type="submit" variant="primary">
              Apply
            </Button>
          </div>
        </form>

        <DataTable aria-label="Orders" className="rounded-none border-0 shadow-none">
          <DataTableHead>
            <tr>
              <DataTableTh className="w-10">
                <Checkbox
                  aria-label="Select all orders"
                  checked={allSelected}
                  onCheckedChange={toggleAll}
                />
              </DataTableTh>
              <DataTableTh>ออเดอร์</DataTableTh>
              <DataTableTh>ช่องทาง</DataTableTh>
              <DataTableTh>ลูกค้า</DataTableTh>
              <DataTableTh>สินค้า</DataTableTh>
              <DataTableTh className="text-right">ยอดรวม</DataTableTh>
              <DataTableTh>ชำระเงิน</DataTableTh>
              <DataTableTh>สถานะ</DataTableTh>
              <DataTableTh>Hold</DataTableTh>
            </tr>
          </DataTableHead>
          <tbody>
            {rows.map((row) => (
              <DataTableRow key={row.id}>
                <DataTableCell>
                  <Checkbox
                    aria-label={`Select order ${row.external_order_id}`}
                    checked={selected.has(row.id)}
                    onCheckedChange={(checked) => {
                      const next = new Set(selected)
                      if (checked) next.add(row.id)
                      else next.delete(row.id)
                      setSelected(next)
                    }}
                  />
                </DataTableCell>
                <DataTableCell>
                  <a
                    href={`#/orders/${row.id}`}
                    className="font-mono text-[12.5px] font-medium text-brand-700 hover:underline"
                  >
                    {row.external_order_id}
                  </a>
                  <div className="mt-0.5 text-[11.5px] text-stone-500 tabular-nums">
                    {formatBangkokDateTime(row.ordered_at)}
                  </div>
                </DataTableCell>
                <DataTableCell>
                  <StatusBadge value={row.channel} kind="channel" />
                </DataTableCell>
                <DataTableCell>{row.phone_masked ?? '—'}</DataTableCell>
                <DataTableCell className="text-stone-400">—</DataTableCell>
                <DataTableCell mono className="text-right">
                  {formatMoney(row.grand_total)}
                </DataTableCell>
                <DataTableCell>
                  <div className="flex flex-wrap gap-1">
                    <StatusBadge value={row.payment_method} kind="payment_method" />
                    <StatusBadge value={row.payment_status} kind="payment" />
                  </div>
                </DataTableCell>
                <DataTableCell>
                  <OrderDisplayBadge
                    order_status={row.order_status}
                    fulfillment_status={row.fulfillment_status}
                    hold_reason={row.hold_reason}
                  />
                </DataTableCell>
                <DataTableCell>
                  <HoldReasonBadge reason={row.hold_reason} />
                </DataTableCell>
              </DataTableRow>
            ))}
          </tbody>
        </DataTable>
      </Card>

      {error ? (
        <p role="alert" className="mt-4 text-[13px] text-red-700">
          {error}
        </p>
      ) : null}

      <TablePager
        summary={total === 0 ? 'No orders' : `${page?.items.length ?? 0} on this page · ${total} matching`}
      >
        <Button
          type="button"
          variant="secondary"
          disabled={backStack.length === 0}
          onClick={() => {
            const stack = [...backStack]
            const prev = stack.pop() ?? null
            setBackStack(stack)
            setCursor(prev)
            writeHash(applied, prev)
          }}
        >
          Previous
        </Button>
        <Button
          type="button"
          variant="secondary"
          disabled={!page?.next_cursor}
          onClick={() => {
            if (!page?.next_cursor) return
            setBackStack([...backStack, cursor ?? ''])
            setCursor(page.next_cursor)
            writeHash(applied, page.next_cursor)
          }}
        >
          Next
        </Button>
      </TablePager>
    </PageContent>
  )
}
