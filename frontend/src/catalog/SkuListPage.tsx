import { useEffect, useState, type FormEvent } from 'react'
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
    <main className="wide">
      <h1>SKUs</h1>
      <form className="toolbar" onSubmit={search}>
        <label>
          Search SKUs
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Code prefix, exact barcode, or name"
          />
        </label>
        <button type="submit" disabled={busy}>
          Search
        </button>
        {canWrite ? <a href="#/catalog/skus/new">New SKU</a> : null}
      </form>
      {error ? <p role="alert">{error}</p> : null}
      {page && page.items.length === 0 ? <p>No SKUs found.</p> : null}
      {page && page.items.length > 0 ? (
        <table>
          <thead>
            <tr>
              <th>Code</th>
              <th>Name</th>
              <th>Product</th>
              <th>Barcode</th>
              <th>Type</th>
              <th>On hand</th>
            </tr>
          </thead>
          <tbody>
            {page.items.map((sku) => (
              <tr key={sku.id}>
                <td>
                  <a href={`#/catalog/skus/${sku.id}`}>{sku.sku_code}</a>
                </td>
                <td>{sku.name}</td>
                <td>{sku.product_name}</td>
                <td>{sku.barcode ?? ''}</td>
                <td>{sku.is_bundle ? `Bundle (${sku.component_count})` : 'SKU'}</td>
                <td>{sku.on_hand ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {page && total > 0 ? (
        <p className="pager">
          <span>
            {offset + 1}–{last} of {total}
          </span>
          <button type="button" disabled={busy || offset === 0} onClick={() => setOffset(Math.max(offset - PAGE_SIZE, 0))}>
            Previous
          </button>
          <button type="button" disabled={busy || last >= total} onClick={() => setOffset(offset + PAGE_SIZE)}>
            Next
          </button>
        </p>
      ) : null}
    </main>
  )
}
