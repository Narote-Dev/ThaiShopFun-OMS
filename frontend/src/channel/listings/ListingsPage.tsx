import { useEffect, useMemo, useRef, useState } from 'react'
import type { Me } from '../../auth/AuthContext'
import { catalogApi, type Sku } from '../../catalog/api'
import { Badge } from '../../ui/Badge'
import { Button } from '../../ui/Button'
import { Card } from '../../ui/Card'
import { Dialog } from '../../ui/Dialog'
import {
  DataTable,
  DataTableCell,
  DataTableHead,
  DataTableRow,
  DataTableTh,
} from '../../ui/DataTable'
import { Input } from '../../ui/Input'
import { Label } from '../../ui/Label'
import { PageContent } from '../../ui/PageContent'
import { PageHeader } from '../../ui/PageHeader'
import { Select } from '../../ui/Select'
import { listingsAccess } from './access'
import { AlertBanner } from '../../ui/Alert'
import { FilterBar } from '../../ui/FilterBar'
import {
  listingsApi,
  listingsMessage,
  type ChannelListing,
  type ListingSyncResponse,
  type ReevalSummary,
} from './api'

const PAGE_SIZE = 25

function parseHash(): {
  channelAccountId: string
  mapped: 'unmapped' | 'mapped' | 'all'
  removedOnly: boolean
  q: string
  offset: number
} {
  const raw = window.location.hash.replace(/^#\/?/, '')
  const [path, query = ''] = raw.split('?')
  if (!path.startsWith('channel/listings')) {
    return { channelAccountId: '', mapped: 'unmapped', removedOnly: false, q: '', offset: 0 }
  }
  const params = new URLSearchParams(query)
  const mappedParam = params.get('mapped')
  const removedOnly = params.get('removed') === 'true'
  let mapped =
    mappedParam === 'true'
      ? 'mapped'
      : mappedParam === 'false'
        ? 'unmapped'
        : mappedParam === 'all'
          ? 'all'
          : 'unmapped'
  if (removedOnly && mapped === 'unmapped') {
    mapped = 'all'
  }
  const offset = Number.parseInt(params.get('offset') ?? '0', 10)
  return {
    channelAccountId: params.get('channel_account_id') ?? '',
    mapped,
    removedOnly,
    q: params.get('q') ?? '',
    offset: Number.isFinite(offset) && offset >= 0 ? offset : 0,
  }
}

function writeHash(
  channelAccountId: string,
  mapped: string,
  removedOnly: boolean,
  q: string,
  offset: number,
) {
  const params = new URLSearchParams()
  if (channelAccountId) params.set('channel_account_id', channelAccountId)
  if (mapped === 'mapped') params.set('mapped', 'true')
  else if (mapped === 'unmapped') params.set('mapped', 'false')
  else if (mapped === 'all') params.set('mapped', 'all')
  if (removedOnly) params.set('removed', 'true')
  if (q) params.set('q', q)
  if (offset > 0) params.set('offset', String(offset))
  const qs = params.toString()
  window.location.hash = qs ? `#/channel/listings?${qs}` : '#/channel/listings'
}

export default function ListingsPage({ me }: { me: Me }) {
  const access = listingsAccess(me)
  const [channelAccountId, setChannelAccountId] = useState(() => parseHash().channelAccountId)
  const [mappedFilter, setMappedFilter] = useState<'unmapped' | 'mapped' | 'all'>(() => parseHash().mapped)
  const [removedOnly, setRemovedOnly] = useState(() => parseHash().removedOnly)
  const [q, setQ] = useState(() => parseHash().q)
  const [items, setItems] = useState<ChannelListing[]>([])
  const [total, setTotal] = useState(0)
  const [offset, setOffset] = useState(() => parseHash().offset)
  const [accounts, setAccounts] = useState<{ id: string; external_shop_id: string }[]>([])
  const [searchDraft, setSearchDraft] = useState(() => parseHash().q)
  const [error, setError] = useState('')
  const [summary, setSummary] = useState<ReevalSummary | null>(null)
  const [syncResult, setSyncResult] = useState<ListingSyncResponse | null>(null)
  const [pickerSku, setPickerSku] = useState<Sku | null>(null)
  const [skuQuery, setSkuQuery] = useState('')
  const [skuHits, setSkuHits] = useState<Sku[]>([])
  const [activeListing, setActiveListing] = useState<ChannelListing | null>(null)
  const [busy, setBusy] = useState(false)
  const searchDebounceBoot = useRef(true)
  const channelAccountLoadBoot = useRef(false)

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
          setChannelAccountId((current) => {
            if (!current) {
              channelAccountLoadBoot.current = true
              return page.items[0].id
            }
            return current
          })
        }
      })
      .catch(() => setAccounts([]))
  }, [])

  useEffect(() => {
    const onHash = () => {
      const parsed = parseHash()
      setChannelAccountId(parsed.channelAccountId)
      setMappedFilter(parsed.mapped)
      setRemovedOnly(parsed.removedOnly)
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
        channelAccountLoadBoot.current = false
        if (searchDraft !== q) {
          setQ(searchDraft)
        }
        return
      }
      if (channelAccountLoadBoot.current) {
        channelAccountLoadBoot.current = false
        if (searchDraft !== q) {
          setQ(searchDraft)
        }
        return
      }
      setQ(searchDraft)
      setOffset(0)
      writeHash(channelAccountId, mappedFilter, removedOnly, searchDraft, 0)
    }, 400)
    return () => window.clearTimeout(timer)
  }, [searchDraft, channelAccountId, mappedFilter, removedOnly, q])

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
      .list(channelAccountId, mappedParam, removedOnly, q, PAGE_SIZE, offset)
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
  }, [channelAccountId, mappedParam, removedOnly, q, offset])

  async function applyFilters() {
    setOffset(0)
    writeHash(channelAccountId, mappedFilter, removedOnly, searchDraft, 0)
  }

  async function saveMapping() {
    if (!activeListing || !pickerSku) return
    setBusy(true)
    setError('')
    try {
      const response = await listingsApi.putMapping(activeListing.id, pickerSku.id)
      setSummary(response.reevaluation)
      closeMapPicker()
      const page = await listingsApi.list(channelAccountId, mappedParam, removedOnly, q, PAGE_SIZE, offset)
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
      const page = await listingsApi.list(channelAccountId, mappedParam, removedOnly, q, PAGE_SIZE, offset)
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
    setSyncResult(null)
    try {
      const result = await listingsApi.syncListings(channelAccountId)
      setSyncResult(result)
      const page = await listingsApi.list(channelAccountId, mappedParam, removedOnly, q, PAGE_SIZE, offset)
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
    <PageContent wide>
      <PageHeader
        title={
          <>
            <span className="sr-only">Channel listings</span>
            <span aria-hidden="true">Listings</span>
          </>
        }
      />
      <Card className="mt-4">
        <div className="grid gap-4 p-4 sm:grid-cols-2 lg:grid-cols-4">
          <div>
            <Label htmlFor="listings-account">Channel account</Label>
            <Select
              id="listings-account"
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
            </Select>
          </div>
          <div>
            <Label htmlFor="listings-mapped">Mapped</Label>
            <Select
              id="listings-mapped"
              aria-label="Mapped filter"
              value={mappedFilter}
              onChange={(e) => setMappedFilter(e.target.value as 'unmapped' | 'mapped' | 'all')}
            >
              <option value="unmapped">Unmapped first</option>
              <option value="mapped">Mapped</option>
              <option value="all">All</option>
            </Select>
          </div>
          <div>
            <Label htmlFor="listings-search">Search</Label>
            <Input
              id="listings-search"
              value={searchDraft}
              onChange={(e) => setSearchDraft(e.target.value)}
              aria-label="Search listings"
            />
          </div>
          <div className="flex flex-wrap items-end gap-2">
            <Button type="button" variant="primary" onClick={() => void applyFilters()}>
              Apply
            </Button>
            {access.canWrite ? (
              <Button type="button" variant="secondary" disabled={busy || !channelAccountId} onClick={() => void syncListings()}>
                Sync listings
              </Button>
            ) : (
              <p role="status" className="text-[13px] text-stone-600">
                Read-only{access.grace ? ' (GRACE)' : ''}
              </p>
            )}
          </div>
        </div>
      </Card>
      {syncResult?.removal_skipped ? (
        <AlertBanner variant="warning" className="mt-4" title="ไม่ได้ลบรายการจาก TSF">
          รายการที่หายไปจาก TSF มีจำนวนมากเกินเกณฑ์ความปลอดภัย ระบบจึงไม่ตั้งสถานะถูกลบ — ลองซิงก์อีกครั้งหรือตรวจสอบที่ช่องทาง TSF
        </AlertBanner>
      ) : null}
      {syncResult ? (
        <Card className="mt-4 p-4" role="status" aria-label="ผลการซิงก์ listings">
          <p className="text-[13px] text-stone-800">
            ดึงมา {syncResult.fetched} · สร้างใหม่ {syncResult.created} · อัปเดต {syncResult.updated} · map
            อัตโนมัติ {syncResult.auto_mapped} · กลับมาขาย {syncResult.revived} · ถูกลบจาก TSF{' '}
            {syncResult.removed} · ประมวลผล hold {syncResult.reevaluated_orders} · รอคิว{' '}
            {syncResult.deferred}
          </p>
        </Card>
      ) : null}
      <FilterBar className="mt-4">
        <Button
          type="button"
          size="sm"
          variant={removedOnly ? 'primary' : 'secondary'}
          aria-pressed={removedOnly}
          onClick={() => {
            const next = !removedOnly
            const mappedForHash = next ? 'all' : mappedFilter
            setRemovedOnly(next)
            if (next) {
              setMappedFilter('all')
            }
            setOffset(0)
            writeHash(channelAccountId, mappedForHash, next, searchDraft, 0)
          }}
        >
          ถูกลบจาก TSF
        </Button>
      </FilterBar>
      {summary ? (
        <p role="status" className="mt-2 text-[13px] text-stone-600">
          Re-evaluated: released {summary.released}, out of stock {summary.out_of_stock}, still held{' '}
          {summary.still_held}, deferred {summary.deferred}
        </p>
      ) : null}
      {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : null}
      <div className="mt-4">
        <DataTable aria-label="Channel listings">
          <DataTableHead>
            <tr>
              <DataTableTh>External SKU</DataTableTh>
              <DataTableTh>Seller SKU</DataTableTh>
              <DataTableTh>Name</DataTableTh>
              <DataTableTh>Mapped</DataTableTh>
              <DataTableTh>Source</DataTableTh>
              <DataTableTh>Held orders</DataTableTh>
              <DataTableTh />
            </tr>
          </DataTableHead>
          <tbody>
            {items.map((row) => (
              <DataTableRow key={row.id}>
                <DataTableCell>
                  {row.external_sku_id}
                  {row.removed_at ? (
                    <Badge variant="muted" className="ml-1 normal-case" title="Removed on channel">
                      {' '}
                      removed
                    </Badge>
                  ) : null}
                </DataTableCell>
                <DataTableCell>{row.seller_sku ?? '—'}</DataTableCell>
                <DataTableCell>{row.name ?? '—'}</DataTableCell>
                <DataTableCell>
                  {row.sku_id
                    ? `${row.sku_code ?? row.sku_id}${row.sku_name ? ` — ${row.sku_name}` : ''}`
                    : 'Not mapped'}
                </DataTableCell>
                <DataTableCell>{row.mapping_source ?? '—'}</DataTableCell>
                <DataTableCell>{row.held_orders}</DataTableCell>
                <DataTableCell>
                  {access.canWrite && !row.removed_at ? (
                    <div className="flex flex-wrap gap-2">
                      <Button type="button" size="sm" onClick={() => openMapPicker(row)}>
                        Map
                      </Button>
                      {row.sku_id ? (
                        <Button type="button" size="sm" variant="ghost" onClick={() => void unmap(row)}>
                          Unmap
                        </Button>
                      ) : null}
                    </div>
                  ) : null}
                </DataTableCell>
              </DataTableRow>
            ))}
          </tbody>
        </DataTable>
      </div>
      <p className="mt-4 text-[13px] text-stone-600">{total} listings</p>
      <div className="mt-2 flex flex-wrap gap-2">
        <Button
          type="button"
          variant="secondary"
          disabled={offset === 0}
          onClick={() => {
            const next = Math.max(0, offset - PAGE_SIZE)
            setOffset(next)
            writeHash(channelAccountId, mappedFilter, removedOnly, searchDraft, next)
          }}
        >
          Previous
        </Button>
        <Button
          type="button"
          variant="secondary"
          disabled={offset + PAGE_SIZE >= total}
          onClick={() => {
            const next = offset + PAGE_SIZE
            setOffset(next)
            writeHash(channelAccountId, mappedFilter, removedOnly, searchDraft, next)
          }}
        >
          Next
        </Button>
      </div>
      <Dialog
        open={activeListing != null}
        onOpenChange={(open) => {
          if (!open) closeMapPicker()
        }}
        title={activeListing ? `Map ${activeListing.external_sku_id}` : 'Map listing'}
      >
        <div className="space-y-4">
          <div>
            <Label htmlFor="picker-sku-search">SKU search</Label>
            <Input
              id="picker-sku-search"
              aria-label="SKU search"
              value={skuQuery}
              onChange={(e) => setSkuQuery(e.target.value)}
            />
          </div>
          <ul className="space-y-1">
            {visibleSkuHits.map((sku) => (
              <li key={sku.id}>
                <button
                  type="button"
                  className="text-left text-[13px] font-medium text-brand-700 hover:underline"
                  onClick={() => setPickerSku(sku)}
                >
                  {sku.sku_code} — {sku.name}
                </button>
              </li>
            ))}
          </ul>
          {pickerSku ? <p className="text-[13px] text-stone-600">Selected: {pickerSku.sku_code}</p> : null}
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="primary" disabled={busy || !pickerSku} onClick={() => void saveMapping()}>
              Save mapping
            </Button>
            <Button type="button" variant="secondary" onClick={() => closeMapPicker()}>
              Cancel
            </Button>
          </div>
        </div>
      </Dialog>
    </PageContent>
  )
}
