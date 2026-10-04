import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import {
  DataTable,
  DataTableCell,
  DataTableHead,
  DataTableRow,
  DataTableTh,
  TablePager,
} from '../ui/DataTable'
import { Input } from '../ui/Input'
import { Label } from '../ui/Label'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'
import { Select } from '../ui/Select'
import { StatusBadge } from '../ui/StatusBadge'
import { cn } from '../ui/cn'
import {
  ORDER_PAGE_SIZE,
  ordersApi,
  ordersMessage,
  type OrderFilters,
  type OrderListItem,
  type OrdersPage,
} from './api'

const TABS = ['', 'READY_TO_PICK', 'PICKING', 'PACKED', 'SHIPPED', 'DELIVERED', 'UNFULFILLED'] as const

function filtersFromHash(): OrderFilters {
  const hash = window.location.hash
  const query = hash.includes('?') ? hash.slice(hash.indexOf('?') + 1) : ''
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

  const tab = applied.fulfillment_status

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

  function selectTab(value: string) {
    const next = { ...applied, fulfillment_status: value }
    setFilters(next)
    setApplied(next)
    setCursor(null)
    setBackStack([])
    writeHash(next, null)
  }

  function apply(event: FormEvent) {
    event.preventDefault()
    setApplied(filters)
    setCursor(null)
    setBackStack([])
    writeHash(filters, null)
  }

  const total = page?.total ?? 0
  const tabLabel = useMemo(
    () => (value: string) => (value === '' ? 'All' : value.replaceAll('_', ' ')),
    [],
  )

  return (
    <PageContent wide>
      <PageHeader
        title="Orders"
        subtitle="จัดการออเดอร์จากทุกช่องทาง"
        actions={
          <a href="#/orders/holds" className="text-[13px] font-medium text-brand-700 hover:underline">
            Hold queue
          </a>
        }
      />

      <nav aria-label="Fulfillment tabs" className="mt-6 flex flex-wrap gap-1 border-b border-stone-200 pb-1">
        {TABS.map((value) => (
          <button
            key={value || 'all'}
            type="button"
            className={cn(
              'rounded-t-lg px-3 py-2 text-[13px] font-medium',
              tab === value
                ? 'border border-b-white border-stone-200 bg-white text-brand-800'
                : 'text-stone-600 hover:bg-stone-100',
            )}
            onClick={() => selectTab(value)}
          >
            {tabLabel(value)}
          </button>
        ))}
      </nav>

      <Card className="mt-4">
        <form
          className="grid gap-4 p-4 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-5"
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
            <Button type="submit" variant="primary">Apply</Button>
          </div>
        </form>
      </Card>

      {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : null}

      <div className="mt-4">
        <DataTable aria-label="Orders">
          <DataTableHead>
            <tr>
              <DataTableTh>Order</DataTableTh>
              <DataTableTh>Fulfillment</DataTableTh>
              <DataTableTh>Payment</DataTableTh>
              <DataTableTh>Hold</DataTableTh>
              <DataTableTh>Phone</DataTableTh>
              <DataTableTh>Total</DataTableTh>
            </tr>
          </DataTableHead>
          <tbody>
            {(page?.items ?? []).map((row) => (
              <DataTableRow key={row.id}>
                <DataTableCell mono>
                  <a href={`#/orders/${row.id}`} className="font-medium text-brand-700 hover:underline">
                    {row.external_order_id}
                  </a>
                </DataTableCell>
                <DataTableCell>
                  <StatusBadge value={row.fulfillment_status} kind="fulfillment" />
                </DataTableCell>
                <DataTableCell>
                  <StatusBadge value={row.payment_status} kind="payment" />
                </DataTableCell>
                <DataTableCell>
                  <StatusBadge value={row.hold_reason} kind="hold" />
                </DataTableCell>
                <DataTableCell>{row.phone_masked ?? '—'}</DataTableCell>
                <DataTableCell mono>{row.grand_total}</DataTableCell>
              </DataTableRow>
            ))}
          </tbody>
        </DataTable>
      </div>

      <TablePager summary={total === 0 ? 'No orders' : `${page?.items.length ?? 0} on this page · ${total} matching`}>
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
