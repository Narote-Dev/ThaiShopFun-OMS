import { useEffect, useMemo, useState, type FormEvent } from 'react'
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
    <main className="wide">
      <h1>Orders</h1>
      <p>
        <a href="#/orders/holds">Hold queue</a>
      </p>
      <nav aria-label="Fulfillment tabs" className="toolbar">
        {TABS.map((value) => (
          <button
            key={value || 'all'}
            type="button"
            className={tab === value ? 'active' : ''}
            onClick={() => selectTab(value)}
          >
            {tabLabel(value)}
          </button>
        ))}
      </nav>
      <form className="toolbar" onSubmit={apply} aria-label="Filter orders">
        <label>
          Search
          <input value={filters.q} onChange={(e) => setFilters({ ...filters, q: e.target.value })} />
        </label>
        <label>
          Hold
          <select
            value={filters.hold_reason}
            onChange={(e) => setFilters({ ...filters, hold_reason: e.target.value })}
          >
            <option value="">Any</option>
            <option value="NONE">None</option>
            <option value="ANY">On hold</option>
            <option value="OUT_OF_STOCK">Out of stock</option>
            <option value="SKU_NOT_MAPPED">SKU not mapped</option>
          </select>
        </label>
        <label>
          Ordered from
          <input
            type="date"
            value={filters.ordered_from}
            onChange={(e) => setFilters({ ...filters, ordered_from: e.target.value })}
          />
        </label>
        <label>
          Ordered to
          <input
            type="date"
            value={filters.ordered_to}
            onChange={(e) => setFilters({ ...filters, ordered_to: e.target.value })}
          />
        </label>
        <button type="submit">Apply</button>
      </form>
      {error ? <p role="alert">{error}</p> : null}
      <table aria-label="Orders">
        <thead>
          <tr>
            <th>Order</th>
            <th>Fulfillment</th>
            <th>Payment</th>
            <th>Hold</th>
            <th>Phone</th>
            <th>Total</th>
          </tr>
        </thead>
        <tbody>
          {(page?.items ?? []).map((row) => (
            <tr key={row.id}>
              <td>
                <a href={`#/orders/${row.id}`}>{row.external_order_id}</a>
              </td>
              <td>{row.fulfillment_status}</td>
              <td>{row.payment_status}</td>
              <td>{row.hold_reason}</td>
              <td>{row.phone_masked ?? '—'}</td>
              <td>{row.grand_total}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p>{total === 0 ? 'No orders' : `${page?.items.length ?? 0} on this page · ${total} matching`}</p>
      <div className="toolbar">
        <button
          type="button"
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
        </button>
        <button
          type="button"
          disabled={!page?.next_cursor}
          onClick={() => {
            if (!page?.next_cursor) return
            setBackStack([...backStack, cursor ?? ''])
            setCursor(page.next_cursor)
            writeHash(applied, page.next_cursor)
          }}
        >
          Next
        </button>
      </div>
    </main>
  )
}
