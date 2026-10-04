import { useEffect, useState, type FormEvent } from 'react'
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
import { catalogApi, messageFor, type Page, type Sku } from './api'

export const PAGE_SIZE = 25

export default function SkuListPage({ canWrite }: { canWrite: boolean }) {
  const [query, setQuery] = useState('')
  const [applied, setApplied] = useState('')
  const [offset, setOffset] = useState(0)
  const [page, setPage] = useState<Page<Sku> | null>(null)
  const [error, setError] = useState('')
  const [loadedKey, setLoadedKey] = useState('')
  const key = `${applied}|${offset}`
  const busy = loadedKey !== key

  // Step 1: Fetch one page for the applied search. Sorting is by sku_code on the server.
  useEffect(() => {
    let active = true
    catalogApi
      .listSkus(applied, PAGE_SIZE, offset)
      .then((next) => {
        if (!active) return
        setPage(next)
        setError('')
        setLoadedKey(key)
      })
      .catch((err: unknown) => {
        if (!active) return
        setPage(null)
        setError(messageFor(err, 'Could not load SKUs'))
        setLoadedKey(key)
      })
    return () => {
      active = false
    }
  }, [applied, offset, key])

  function search(event: FormEvent) {
    event.preventDefault()
    setOffset(0)
    setApplied(query)
  }

  const total = page?.total ?? 0
  const last = Math.min(offset + PAGE_SIZE, total)
  return (
    <PageContent wide>
      <PageHeader title="SKUs" />
      <Card className="mt-4">
        <form className="flex flex-wrap items-end gap-4 p-4" onSubmit={search}>
          <div className="min-w-[240px] flex-1">
            <Label htmlFor="sku-search">Search SKUs</Label>
            <Input
              id="sku-search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Code prefix, exact barcode, or name"
            />
          </div>
          <Button type="submit" variant="primary" disabled={busy}>
            Search
          </Button>
          {canWrite ? (
            <a href="#/catalog/skus/new" className="text-[13px] font-medium text-brand-700 hover:underline">
              New SKU
            </a>
          ) : null}
        </form>
      </Card>
      {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : null}
      {page && page.items.length === 0 ? <p className="mt-4 text-[13px] text-stone-600">No SKUs found.</p> : null}
      {page && page.items.length > 0 ? (
        <div className="mt-4">
          <DataTable aria-label="SKUs">
            <DataTableHead>
              <tr>
                <DataTableTh>Code</DataTableTh>
                <DataTableTh>Name</DataTableTh>
                <DataTableTh>Product</DataTableTh>
                <DataTableTh>Barcode</DataTableTh>
                <DataTableTh>Type</DataTableTh>
                <DataTableTh>On hand</DataTableTh>
                <DataTableTh>Stock</DataTableTh>
              </tr>
            </DataTableHead>
            <tbody>
              {page.items.map((sku) => (
                <DataTableRow key={sku.id}>
                  <DataTableCell mono>
                    <a href={`#/catalog/skus/${sku.id}`} className="font-medium text-brand-700 hover:underline">
                      {sku.sku_code}
                    </a>
                  </DataTableCell>
                  <DataTableCell>{sku.name}</DataTableCell>
                  <DataTableCell>{sku.product_name}</DataTableCell>
                  <DataTableCell>{sku.barcode ?? ''}</DataTableCell>
                  <DataTableCell>{sku.is_bundle ? `Bundle (${sku.component_count})` : 'SKU'}</DataTableCell>
                  <DataTableCell>{sku.on_hand ?? '—'}</DataTableCell>
                  <DataTableCell>
                    {/* Change: T08A stock history per SKU. Bundles have no stock of their own. */}
                    {sku.is_bundle ? null : (
                      <a href={`#/catalog/skus/${sku.id}/history`} className="font-medium text-brand-700 hover:underline">
                        History
                      </a>
                    )}
                  </DataTableCell>
                </DataTableRow>
              ))}
            </tbody>
          </DataTable>
        </div>
      ) : null}
      {page && total > 0 ? (
        <TablePager
          summary={
            <span>
              {offset + 1}–{last} of {total}
            </span>
          }
        >
          <Button
            type="button"
            variant="secondary"
            disabled={busy || offset === 0}
            onClick={() => setOffset(Math.max(offset - PAGE_SIZE, 0))}
          >
            Previous
          </Button>
          <Button type="button" variant="secondary" disabled={busy || last >= total} onClick={() => setOffset(offset + PAGE_SIZE)}>
            Next
          </Button>
        </TablePager>
      ) : null}
    </PageContent>
  )
}
