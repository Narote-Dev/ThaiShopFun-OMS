import { useEffect, useMemo, useRef, useState } from 'react'
import type { Me } from '../../auth/AuthContext'
import { catalogApi, type Sku } from '../../catalog/api'
import { listingsAccess } from './access'
import { listingsApi, listingsMessage, type ChannelListing, type ReevalSummary } from './api'

const PAGE_SIZE = 25

function parseHash(): {
  channelAccountId: string
  mapped: 'unmapped' | 'mapped' | 'all'
  q: string
  offset: number
} {
  const raw = window.location.hash.replace(/^#\/?/, '')
  const [path, query = ''] = raw.split('?')
  if (!path.startsWith('channel/listings')) {
    return { channelAccountId: '', mapped: 'unmapped', q: '', offset: 0 }
  }
  const params = new URLSearchParams(query)
  const mappedParam = params.get('mapped')
  const mapped =
    mappedParam === 'true'
      ? 'mapped'
      : mappedParam === 'false'
        ? 'unmapped'
        : mappedParam === 'all'
          ? 'all'
          : 'unmapped'
  const offset = Number.parseInt(params.get('offset') ?? '0', 10)
  return {
    channelAccountId: params.get('channel_account_id') ?? '',
    mapped,
    q: params.get('q') ?? '',
    offset: Number.isFinite(offset) && offset >= 0 ? offset : 0,
  }
}

function writeHash(channelAccountId: string, mapped: string, q: string, offset: number) {
  const params = new URLSearchParams()
  if (channelAccountId) params.set('channel_account_id', channelAccountId)
  if (mapped === 'mapped') params.set('mapped', 'true')
  else if (mapped === 'unmapped') params.set('mapped', 'false')
  else if (mapped === 'all') params.set('mapped', 'all')
  if (q) params.set('q', q)
  if (offset > 0) params.set('offset', String(offset))
  const qs = params.toString()
  window.location.hash = qs ? `#/channel/listings?${qs}` : '#/channel/listings'
}

export default function ListingsPage({ me }: { me: Me }) {
  const access = listingsAccess(me)
  const [channelAccountId, setChannelAccountId] = useState(() => parseHash().channelAccountId)
  const [mappedFilter, setMappedFilter] = useState<'unmapped' | 'mapped' | 'all'>(() => parseHash().mapped)
  const [q, setQ] = useState(() => parseHash().q)
  const [items, setItems] = useState<ChannelListing[]>([])
  const [total, setTotal] = useState(0)
  const [offset, setOffset] = useState(() => parseHash().offset)
  const [accounts, setAccounts] = useState<{ id: string; external_shop_id: string }[]>([])
  const [searchDraft, setSearchDraft] = useState(() => parseHash().q)
  const [error, setError] = useState('')
  const [summary, setSummary] = useState<ReevalSummary | null>(null)
  const [syncResult, setSyncResult] = useState<string>('')
  const [pickerSku, setPickerSku] = useState<Sku | null>(null)
  const [skuQuery, setSkuQuery] = useState('')
  const [skuHits, setSkuHits] = useState<Sku[]>([])
  const [activeListing, setActiveListing] = useState<ChannelListing | null>(null)
  const [busy, setBusy] = useState(false)
  const searchDebounceBoot = useRef(true)

  const mappedParam = useMemo(() => {
    if (mappedFilter === 'all') return null
    return mappedFilter === 'mapped'
  }, [mappedFilter])

  useEffect(() => {
    listingsApi
      .listAccounts()
      .then((page) => {
        setAccounts(page.items.map((row) => ({ id: row.id, external_shop_id: row.external_shop_id })))
        if (page.items[0]) {
          setChannelAccountId((current) => current || page.items[0].id)
        }
      })
      .catch(() => setAccounts([]))
  }, [])

  useEffect(() => {
    const onHash = () => {
      const parsed = parseHash()
      setChannelAccountId(parsed.channelAccountId)
      setMappedFilter(parsed.mapped)
      setQ(parsed.q)
      setSearchDraft(parsed.q)
      setOffset(parsed.offset)
    }
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (searchDebounceBoot.current) {
        searchDebounceBoot.current = false
        if (searchDraft !== q) {
          setQ(searchDraft)
        }
        return
      }
      setQ(searchDraft)
      setOffset(0)
      writeHash(channelAccountId, mappedFilter, searchDraft, 0)
    }, 400)
    return () => window.clearTimeout(timer)
  }, [searchDraft, channelAccountId, mappedFilter, q])

  function openMapPicker(row: ChannelListing) {
    setPickerSku(null)
    setSkuQuery('')
    setActiveListing(row)
  }

  function closeMapPicker() {
    setActiveListing(null)
    setPickerSku(null)
    setSkuQuery('')
  }

  useEffect(() => {
    if (!channelAccountId) return
    let active = true
    listingsApi
      .list(channelAccountId, mappedParam, q, PAGE_SIZE, offset)
      .then((page) => {
        if (!active) return
        setItems(page.items)
        setTotal(page.total)
        setError('')
      })
      .catch((err: unknown) => {
        if (active) setError(listingsMessage(err, 'Could not load listings'))
      })
    return () => {
      active = false
    }
  }, [channelAccountId, mappedParam, q, offset])

  async function applyFilters() {
    setOffset(0)
    writeHash(channelAccountId, mappedFilter, searchDraft, 0)
  }

  async function saveMapping() {
    if (!activeListing || !pickerSku) return
    setBusy(true)
    setError('')
    try {
      const response = await listingsApi.putMapping(activeListing.id, pickerSku.id)
      setSummary(response.reevaluation)
      closeMapPicker()
      const page = await listingsApi.list(channelAccountId, mappedParam, q, PAGE_SIZE, offset)
      setItems(page.items)
      setTotal(page.total)
    } catch (err: unknown) {
      setError(listingsMessage(err, 'Mapping failed'))
    } finally {
      setBusy(false)
    }
  }

  async function unmap(listing: ChannelListing) {
    const ok = window.confirm(`Unmap ${listing.external_sku_id}?`)
    if (!ok) return
    setBusy(true)
    try {
      await listingsApi.deleteMapping(listing.id)
      const page = await listingsApi.list(channelAccountId, mappedParam, q, PAGE_SIZE, offset)
      setItems(page.items)
      setTotal(page.total)
    } catch (err: unknown) {
      setError(listingsMessage(err, 'Unmap failed'))
    } finally {
      setBusy(false)
    }
  }

  async function syncListings() {
    if (!channelAccountId) return
    setBusy(true)
    setSyncResult('')
    try {
      const result = await listingsApi.syncListings(channelAccountId)
      setSyncResult(
        `Fetched ${result.fetched}, created ${result.created}, updated ${result.updated}, auto-mapped ${result.auto_mapped}, re-evaluated ${result.reevaluated_orders} orders`,
      )
      const page = await listingsApi.list(channelAccountId, mappedParam, q, PAGE_SIZE, offset)
      setItems(page.items)
      setTotal(page.total)
    } catch (err: unknown) {
      setError(listingsMessage(err, 'Sync failed'))
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    const query = skuQuery.trim()
    if (!query) return
    let active = true
    catalogApi
      .listSkus(query, 10, 0)
      .then((page) => {
        if (active) setSkuHits(page.items)
      })
      .catch(() => {
        if (active) setSkuHits([])
      })
    return () => {
      active = false
    }
  }, [skuQuery])

  const visibleSkuHits = skuQuery.trim() ? skuHits : []

  return (
    <main className="wide">
      <p><a href="#/orders">← Orders</a></p>
      <h1>Channel listings</h1>
      <label>
        Channel account
        <select
          aria-label="Channel account"
          value={channelAccountId}
          onChange={(e) => setChannelAccountId(e.target.value)}
        >
          <option value="">Select account…</option>
          {accounts.map((row) => (
            <option key={row.id} value={row.id}>
              {row.external_shop_id} ({row.id.slice(0, 8)}…)
            </option>
          ))}
        </select>
      </label>
      <label>
        Mapped
        <select
          aria-label="Mapped filter"
          value={mappedFilter}
          onChange={(e) => setMappedFilter(e.target.value as 'unmapped' | 'mapped' | 'all')}
        >
          <option value="unmapped">Unmapped first</option>
          <option value="mapped">Mapped</option>
          <option value="all">All</option>
        </select>
      </label>
      <label>
        Search
        <input
          value={searchDraft}
          onChange={(e) => setSearchDraft(e.target.value)}
          aria-label="Search listings"
        />
      </label>
      <button type="button" onClick={() => void applyFilters()}>Apply</button>
      {access.canWrite ? (
        <button type="button" disabled={busy || !channelAccountId} onClick={() => void syncListings()}>
          Sync listings
        </button>
      ) : (
        <p role="status">Read-only{access.grace ? ' (GRACE)' : ''}</p>
      )}
      {syncResult ? <p role="status">{syncResult}</p> : null}
      {summary ? (
        <p role="status">
          Re-evaluated: released {summary.released}, out of stock {summary.out_of_stock}, still held{' '}
          {summary.still_held}, deferred {summary.deferred}
        </p>
      ) : null}
      {error ? <p role="alert">{error}</p> : null}
      <table aria-label="Channel listings">
        <thead>
          <tr>
            <th>External SKU</th>
            <th>Seller SKU</th>
            <th>Name</th>
            <th>Mapped</th>
            <th>Source</th>
            <th>Held orders</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {items.map((row) => (
            <tr key={row.id}>
              <td>
                {row.external_sku_id}
                {row.removed_at ? (
                  <span className="badge removed" title="Removed on channel"> removed</span>
                ) : null}
              </td>
              <td>{row.seller_sku ?? '—'}</td>
              <td>{row.name ?? '—'}</td>
              <td>
                {row.sku_id
                  ? `${row.sku_code ?? row.sku_id}${row.sku_name ? ` — ${row.sku_name}` : ''}`
                  : 'Not mapped'}
              </td>
              <td>{row.mapping_source ?? '—'}</td>
              <td>{row.held_orders}</td>
              <td>
                {access.canWrite ? (
                  <>
                    <button type="button" onClick={() => openMapPicker(row)}>Map</button>
                    {row.sku_id ? (
                      <button type="button" onClick={() => void unmap(row)}>Unmap</button>
                    ) : null}
                  </>
                ) : null}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p>{total} listings</p>
      <button
        type="button"
        disabled={offset === 0}
        onClick={() => {
          const next = Math.max(0, offset - PAGE_SIZE)
          setOffset(next)
          writeHash(channelAccountId, mappedFilter, searchDraft, next)
        }}
      >
        Previous
      </button>
      <button
        type="button"
        disabled={offset + PAGE_SIZE >= total}
        onClick={() => {
          const next = offset + PAGE_SIZE
          setOffset(next)
          writeHash(channelAccountId, mappedFilter, searchDraft, next)
        }}
      >
        Next
      </button>
      {activeListing ? (
        <section aria-label="SKU picker">
          <h2>Map {activeListing.external_sku_id}</h2>
          <label>
            SKU search
            <input value={skuQuery} onChange={(e) => setSkuQuery(e.target.value)} />
          </label>
          <ul>
            {visibleSkuHits.map((sku) => (
              <li key={sku.id}>
                <button type="button" onClick={() => setPickerSku(sku)}>
                  {sku.sku_code} — {sku.name}
                </button>
              </li>
            ))}
          </ul>
          {pickerSku ? <p>Selected: {pickerSku.sku_code}</p> : null}
          <button type="button" disabled={busy || !pickerSku} onClick={() => void saveMapping()}>
            Save mapping
          </button>
          <button type="button" onClick={() => closeMapPicker()}>Cancel</button>
        </section>
      ) : null}
    </main>
  )
}
