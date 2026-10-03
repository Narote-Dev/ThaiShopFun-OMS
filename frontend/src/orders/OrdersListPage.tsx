import { useEffect, useMemo, useState, type FormEvent } from 'react'
import type { Page } from '../catalog/api'
import {
  ORDER_PAGE_SIZE,
  ordersApi,
  ordersMessage,
  type OrderFilters,
  type OrderListItem,
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

function writeHash(filters: OrderFilters, offset: number) {
  const params = new URLSearchParams()
  if (filters.fulfillment_status) params.set('fulfillment_status', filters.fulfillment_status)
  if (filters.order_status) params.set('order_status', filters.order_status)
  if (filters.payment_status) params.set('payment_status', filters.payment_status)
  if (filters.hold_reason) params.set('hold_reason', filters.hold_reason)
  if (filters.q) params.set('q', filters.q)
  if (filters.ordered_from) params.set('ordered_from', filters.ordered_from)
  if (filters.ordered_to) params.set('ordered_to', filters.ordered_to)
  if (offset > 0) params.set('offset', String(offset))
  const qs = params.toString()
  window.location.hash = qs ? `#/orders?${qs}` : '#/orders'
}

function initialOffset(): number {
  const off = Number(new URLSearchParams(window.location.hash.split('?')[1] ?? '').get('offset') ?? 0)
  return Number.isFinite(off) ? off : 0
}

export default function OrdersListPage() {
  const [filters, setFilters] = useState<OrderFilters>(filtersFromHash)
  const [applied, setApplied] = useState<OrderFilters>(filtersFromHash)
  const [offset, setOffset] = useState(initialOffset)
  const [page, setPage] = useState<Page<OrderListItem> | null>(null)
  const [error, setError] = useState('')

  const tab = applied.fulfillment_status

  useEffect(() => {
    let active = true
    ordersApi
      .list(applied, ORDER_PAGE_SIZE, offset)
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
  }, [applied, offset])

  function selectTab(value: string) {
    const next = { ...applied, fulfillment_status: value }
    setFilters(next)
    setApplied(next)
    setOffset(0)
    writeHash(next, 0)
  }

  function apply(event: FormEvent) {
    event.preventDefault()
    setApplied(filters)
    setOffset(0)
    writeHash(filters, 0)
  }

  const total = page?.total ?? 0
  const last = Math.min(offset + ORDER_PAGE_SIZE, total)
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
      <p>
        {total === 0 ? 'No orders' : `Showing ${offset + 1}–${last} of ${total}`}
      </p>
      <div className="toolbar">
        <button
          type="button"
          disabled={offset === 0}
          onClick={() => {
            const next = Math.max(0, offset - ORDER_PAGE_SIZE)
            setOffset(next)
            writeHash(applied, next)
          }}
        >
          Previous
        </button>
        <button
          type="button"
          disabled={last >= total}
          onClick={() => {
            const next = offset + ORDER_PAGE_SIZE
            setOffset(next)
            writeHash(applied, next)
          }}
        >
          Next
        </button>
      </div>
    </main>
  )
}
