import { useEffect, useState, type FormEvent } from 'react'
import { catalogApi, type Warehouse } from '../catalog/api'
import { LEDGER_REASONS, stockApi, stockMessage, TYPE_LABELS, type HistoryEntry, type HistoryFilters, type HistoryLink } from './api'

const emptyFilters: HistoryFilters = { reason: '', from: '', to: '', warehouse_id: '' }

function signed(n: number): string {
  return n > 0 ? `+${n}` : String(n)
}

function Source({ link }: { link: HistoryLink | null }) {
  if (!link) return null
  if (link.kind === 'stock_document' && link.document_id) {
    const label = `${link.document_type ? TYPE_LABELS[link.document_type] : 'Document'}${link.reference_no ? ` ${link.reference_no}` : ''}`
    return <a href={`#/stock/documents/${link.document_id}`}>{label}</a>
  }
  if (link.kind === 'reservation') {
    // Order pages arrive with T12; until then the order ref is shown as text.
    if (link.order_ref) return <span>Order {link.order_ref}</span>
    return <span>Checkout hold</span>
  }
  if (link.kind === 'return_line') return <span>Return line {link.return_line_id}</span>
  return null
}

/** Every ledger entry of one SKU, newest first, with filters and keyset "Load more". */
export default function StockHistoryPage({ skuId }: { skuId: string }) {
  const [filters, setFilters] = useState<HistoryFilters>(emptyFilters)
  const [applied, setApplied] = useState<HistoryFilters>(emptyFilters)
  const [items, setItems] = useState<HistoryEntry[]>([])
  const [sku, setSku] = useState<{ sku_code: string; name: string } | null>(null)
  const [cursor, setCursor] = useState<string | null>(null)
  const [warehouses, setWarehouses] = useState<Warehouse[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  // Step 1: First page for the applied filters, and the warehouse list for the filter.
  useEffect(() => {
    let active = true
    Promise.all([stockApi.history(skuId, applied, null), catalogApi.listWarehouses()])
      .then(([page, list]) => {
        if (!active) return
        setSku(page.sku)
        setItems(page.items)
        setCursor(page.next_cursor)
        setWarehouses(list.items)
        setError('')
      })
      .catch((err: unknown) => {
        if (!active) return
        setItems([])
        setCursor(null)
        setError(stockMessage(err, 'Could not load the stock history'))
      })
    return () => {
      active = false
    }
  }, [skuId, applied])

  function apply(event: FormEvent) {
    event.preventDefault()
    setApplied(filters)
  }

  async function more() {
    // Step 2: The next page continues after the last entry shown.
    if (!cursor) return
    setBusy(true)
    try {
      const page = await stockApi.history(skuId, applied, cursor)
      setItems((current) => [...current, ...page.items])
      setCursor(page.next_cursor)
    } catch (err) {
      setError(stockMessage(err, 'Could not load more entries'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="wide">
      <p>
        <a href={`#/catalog/skus/${skuId}`}>Back to SKU</a>
      </p>
      <h1>Stock history{sku ? ` · ${sku.sku_code}` : ''}</h1>
      {sku ? <p>{sku.name}</p> : null}
      <form className="toolbar" onSubmit={apply} aria-label="Filter stock history">
        <label>
          Reason
          <select value={filters.reason} onChange={(event) => setFilters({ ...filters, reason: event.target.value })}>
            <option value="">All reasons</option>
            {LEDGER_REASONS.map((reason) => (
              <option key={reason} value={reason}>
                {reason}
              </option>
            ))}
          </select>
        </label>
        <label>
          Warehouse
          <select
            value={filters.warehouse_id}
            onChange={(event) => setFilters({ ...filters, warehouse_id: event.target.value })}
          >
            <option value="">All warehouses</option>
            {warehouses.map((w) => (
              <option key={w.id} value={w.id}>
                {w.code}
              </option>
            ))}
          </select>
        </label>
        <label>
          From
          <input type="date" value={filters.from} onChange={(event) => setFilters({ ...filters, from: event.target.value })} />
        </label>
        <label>
          To
          <input type="date" value={filters.to} onChange={(event) => setFilters({ ...filters, to: event.target.value })} />
        </label>
        <button type="submit">Filter</button>
      </form>
      {error ? <p role="alert">{error}</p> : null}
      {!error && items.length === 0 ? <p>No stock movements.</p> : null}
      {items.length > 0 ? (
        <table aria-label="Stock movements">
          <thead>
            <tr>
              <th>When</th>
              <th>Warehouse</th>
              <th>Reason</th>
              <th>On hand</th>
              <th>Reserved</th>
              <th>On hand after</th>
              <th>Reserved after</th>
              <th>By</th>
              <th>Source</th>
            </tr>
          </thead>
          <tbody>
            {items.map((entry) => (
              <tr key={entry.id}>
                <td>{new Date(entry.created_at).toLocaleString()}</td>
                <td>{entry.warehouse_code}</td>
                <td>{entry.reason}</td>
                <td>{signed(entry.delta_on_hand)}</td>
                <td>{signed(entry.delta_reserved)}</td>
                <td>{entry.on_hand_after}</td>
                <td>{entry.reserved_after}</td>
                <td>{entry.actor ?? ''}</td>
                <td>
                  <Source link={entry.link} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {cursor ? (
        <p className="pager">
          <button type="button" disabled={busy} onClick={() => void more()}>
            Load more
          </button>
        </p>
      ) : null}
    </main>
  )
}
