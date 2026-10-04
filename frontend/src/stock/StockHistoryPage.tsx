import { useEffect, useState, type FormEvent } from 'react'
import { catalogApi, type Warehouse } from '../catalog/api'
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
import { LEDGER_REASONS, stockApi, stockMessage, TYPE_LABELS, type HistoryEntry, type HistoryFilters, type HistoryLink } from './api'

const emptyFilters: HistoryFilters = { reason: '', from: '', to: '', warehouse_id: '' }

function signed(n: number): string {
  return n > 0 ? `+${n}` : String(n)
}

function Source({ link }: { link: HistoryLink | null }) {
  if (!link) return null
  if (link.kind === 'stock_document' && link.document_id) {
    const label = `${link.document_type ? TYPE_LABELS[link.document_type] : 'Document'}${link.reference_no ? ` ${link.reference_no}` : ''}`
    return <a href={`#/stock/documents/${link.document_id}`} className="font-medium text-brand-700 hover:underline">{label}</a>
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
    <PageContent wide>
      <p className="text-[13px]">
        <a href={`#/catalog/skus/${skuId}`} className="font-medium text-brand-700 hover:underline">
          Back to SKU
        </a>
      </p>
      <PageHeader className="mt-2" title={`Stock history${sku ? ` · ${sku.sku_code}` : ''}`} />
      {sku ? <p className="mt-1 text-[13px] text-stone-600">{sku.name}</p> : null}
      <Card className="mt-4">
        <form className="grid gap-4 p-4 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-5" onSubmit={apply} aria-label="Filter stock history">
          <div>
            <Label htmlFor="history-reason">Reason</Label>
            <Select id="history-reason" value={filters.reason} onChange={(event) => setFilters({ ...filters, reason: event.target.value })}>
              <option value="">All reasons</option>
              {LEDGER_REASONS.map((reason) => (
                <option key={reason} value={reason}>
                  {reason}
                </option>
              ))}
            </Select>
          </div>
          <div>
            <Label htmlFor="history-wh">Warehouse</Label>
            <Select
              id="history-wh"
              value={filters.warehouse_id}
              onChange={(event) => setFilters({ ...filters, warehouse_id: event.target.value })}
            >
              <option value="">All warehouses</option>
              {warehouses.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.code}
                </option>
              ))}
            </Select>
          </div>
          <div>
            <Label htmlFor="history-from">From</Label>
            <Input id="history-from" type="date" value={filters.from} onChange={(event) => setFilters({ ...filters, from: event.target.value })} />
          </div>
          <div>
            <Label htmlFor="history-to">To</Label>
            <Input id="history-to" type="date" value={filters.to} onChange={(event) => setFilters({ ...filters, to: event.target.value })} />
          </div>
          <div className="flex items-end">
            <Button type="submit" variant="primary">
              Filter
            </Button>
          </div>
        </form>
      </Card>
      {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : null}
      {!error && items.length === 0 ? <p className="mt-4 text-[13px] text-stone-600">No stock movements.</p> : null}
      {items.length > 0 ? (
        <div className="mt-4">
          <DataTable aria-label="Stock movements">
            <DataTableHead>
              <tr>
                <DataTableTh>When</DataTableTh>
                <DataTableTh>Warehouse</DataTableTh>
                <DataTableTh>Reason</DataTableTh>
                <DataTableTh>On hand</DataTableTh>
                <DataTableTh>Reserved</DataTableTh>
                <DataTableTh>On hand after</DataTableTh>
                <DataTableTh>Reserved after</DataTableTh>
                <DataTableTh>By</DataTableTh>
                <DataTableTh>Source</DataTableTh>
              </tr>
            </DataTableHead>
            <tbody>
              {items.map((entry) => (
                <DataTableRow key={entry.id}>
                  <DataTableCell>{new Date(entry.created_at).toLocaleString()}</DataTableCell>
                  <DataTableCell>{entry.warehouse_code}</DataTableCell>
                  <DataTableCell>{entry.reason}</DataTableCell>
                  <DataTableCell mono>{signed(entry.delta_on_hand)}</DataTableCell>
                  <DataTableCell mono>{signed(entry.delta_reserved)}</DataTableCell>
                  <DataTableCell>{entry.on_hand_after}</DataTableCell>
                  <DataTableCell>{entry.reserved_after}</DataTableCell>
                  <DataTableCell>{entry.actor ?? ''}</DataTableCell>
                  <DataTableCell>
                    <Source link={entry.link} />
                  </DataTableCell>
                </DataTableRow>
              ))}
            </tbody>
          </DataTable>
        </div>
      ) : null}
      {cursor ? (
        <TablePager summary={null}>
          <Button type="button" variant="secondary" disabled={busy} onClick={() => void more()}>
            Load more
          </Button>
        </TablePager>
      ) : null}
    </PageContent>
  )
}
