import { useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { Me } from '../../auth/AuthContext'
import { catalogApi, type Sku } from '../../catalog/api'
import { Badge } from '../../ui/Badge'
import { Button } from '../../ui/Button'
import { ConfirmationDialog } from '../../ui/ConfirmationDialog'
import { ToastContext } from '../../ui/toast-context'
import { Checkbox } from '../../ui/Checkbox'
import { StatusBadge } from '../../ui/StatusBadge'
import { KpiCard } from '../../ui/Card'
import { messageTh } from '../../ui/labels'
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
import { labelTh } from '../../ui/labels'
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
  let mapped: 'unmapped' | 'mapped' | 'all' =
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
  const toast = useContext(ToastContext)
  const [channelAccountId, setChannelAccountId] = useState(() => parseHash().channelAccountId)
  const [mappedFilter, setMappedFilter] = useState<'unmapped' | 'mapped' | 'all'>(() => parseHash().mapped)
  const [removedOnly, setRemovedOnly] = useState(() => parseHash().removedOnly)
  const [q, setQ] = useState(() => parseHash().q)
  const [items, setItems] = useState<ChannelListing[]>([])
  const [total, setTotal] = useState(0)
  const [mode, setMode] = useState('')
  const [coverage, setCoverage] = useState(0)
  const [stockFilter, setStockFilter] = useState<boolean | null>(null)
  const [selected, setSelected] = useState<string[]>([])
  const [bulkEnabled, setBulkEnabled] = useState<boolean | null>(null)
  const [notice, setNotice] = useState('')
  const canControl = access.canWrite && mode === 'CONTROL'
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
  const [unmapListing, setUnmapListing] = useState<ChannelListing | null>(null)
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
      .list(channelAccountId, mappedParam, removedOnly, q, PAGE_SIZE, offset, stockFilter)
      .then((page) => {
        if (!active) return
        setSelected([])
        setItems(page.items)
        setTotal(page.total)
        setMode(page.mode ?? '')
        setCoverage(page.allowlist_coverage_pct ?? 0)
        setError('')
      })
      .catch((err: unknown) => {
        if (active) setError(listingsMessage(err, 'โหลดรายการขายไม่สำเร็จ'))
      })
    return () => {
      active = false
    }
  }, [channelAccountId, mappedParam, removedOnly, q, offset, stockFilter])

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
      const page = await listingsApi.list(channelAccountId, mappedParam, removedOnly, q, PAGE_SIZE, offset, stockFilter)
      setItems(page.items)
      setTotal(page.total)
      setMode(page.mode ?? '')
      setCoverage(page.allowlist_coverage_pct ?? 0)
    } catch (err: unknown) {
      setError(listingsMessage(err, 'จับคู่ไม่สำเร็จ'))
    } finally {
      setBusy(false)
    }
  }

  async function unmap(listing: ChannelListing) {
    if (busy) return
    setBusy(true)
    try {
      await listingsApi.deleteMapping(listing.id)
      const page = await listingsApi.list(channelAccountId, mappedParam, removedOnly, q, PAGE_SIZE, offset, stockFilter)
      setItems(page.items)
      setTotal(page.total)
      setMode(page.mode ?? '')
      setCoverage(page.allowlist_coverage_pct ?? 0)
    } catch (err: unknown) {
      setError(listingsMessage(err, 'ยกเลิกการจับคู่ไม่สำเร็จ'))
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
      const page = await listingsApi.list(channelAccountId, mappedParam, removedOnly, q, PAGE_SIZE, offset, stockFilter)
      setItems(page.items)
      setTotal(page.total)
      setMode(page.mode ?? '')
      setCoverage(page.allowlist_coverage_pct ?? 0)
    } catch (err: unknown) {
      setError(listingsMessage(err, 'ซิงก์ไม่สำเร็จ'))
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

  async function changeControl(enabled: boolean, listingId?: string) {
    if (!canControl || busy) return
    setBusy(true)
    setError('')
    try {
      const result = listingId
        ? await listingsApi.setStockControl(listingId, enabled)
        : await listingsApi.bulkStockControl(channelAccountId, selected, enabled)
      setBulkEnabled(null)
      setSelected([])
      if (toast) toast.push(`ปรับปรุงแล้ว ${result.changed} รายการ`)
      else setNotice(`ปรับปรุงแล้ว ${result.changed} รายการ`)
      const page = await listingsApi.list(channelAccountId, mappedParam, removedOnly, q, PAGE_SIZE, offset, stockFilter)
      setItems(page.items)
      setTotal(page.total)
      setMode(page.mode ?? '')
      setCoverage(page.allowlist_coverage_pct ?? 0)
    } catch (err) {
      setError(messageTh(listingsMessage(err, ''), 'ปรับการคุมสต็อกไม่สำเร็จ กรุณาตรวจสอบว่ารายการจับคู่แล้วและยังไม่ถูกลบ'))
    } finally { setBusy(false) }
  }

  const visibleSkuHits = skuQuery.trim() ? skuHits : []

  return (
    <PageContent wide>
      <PageHeader
        title={
          <>
            <span className="sr-only">รายการขายจากช่องทาง</span>
            <span aria-hidden="true">รายการขาย</span>
          </>
        }
      />
      <KpiCard label="ครอบคลุมรายการที่อนุญาต" value={`${coverage.toFixed(1)}%`} icon={null}
        hint={<StatusBadge value={mode} kind="generic" />} />
      <Card className="mt-4">
        <div className="grid gap-4 p-4 sm:grid-cols-2 lg:grid-cols-4">
          <div>
            <Label htmlFor="listings-account">บัญชีช่องทาง</Label>
            <Select
              id="listings-account"
              aria-label="บัญชีช่องทาง"
              value={channelAccountId}
              onChange={(e) => setChannelAccountId(e.target.value)}
            >
              <option value="">เลือกบัญชี…</option>
              {accounts.map((row) => (
                <option key={row.id} value={row.id}>
                  {row.external_shop_id} ({row.id.slice(0, 8)}…)
                </option>
              ))}
            </Select>
          </div>
          <div>
            <Label htmlFor="listings-mapped">จับคู่แล้ว</Label>
            <Select
              id="listings-mapped"
              aria-label="ตัวกรองการจับคู่"
              value={mappedFilter}
              onChange={(e) => setMappedFilter(e.target.value as 'unmapped' | 'mapped' | 'all')}
            >
              <option value="unmapped">ยังไม่จับคู่ก่อน</option>
              <option value="mapped">จับคู่แล้ว</option>
              <option value="all">ทั้งหมด</option>
            </Select>
          </div>
          <div>
            <Label htmlFor="listings-search">ค้นหา</Label>
            <Input
              id="listings-search"
              value={searchDraft}
              onChange={(e) => setSearchDraft(e.target.value)}
              aria-label="ค้นหารายการขาย"
            />
          </div>
          <div className="flex flex-wrap items-end gap-2">
            <Button type="button" variant="primary" onClick={() => void applyFilters()}>
              ใช้ตัวกรอง
            </Button>
            {access.canWrite ? (
              <Button type="button" variant="secondary" disabled={busy || !channelAccountId} onClick={() => void syncListings()}>
                ซิงก์รายการขาย
              </Button>
            ) : (
              <p role="status" className="text-[13px] text-stone-600">
                อ่านอย่างเดียว{access.grace ? ' (รอต่ออายุ)' : ''}
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
        <Card className="mt-4 p-4" role="status" aria-label="ผลการซิงก์รายการขาย">
          <p className="text-[13px] text-stone-800">
            ดึงมา {syncResult.fetched} · สร้างใหม่ {syncResult.created} · อัปเดต {syncResult.updated} · จับคู่
            อัตโนมัติ {syncResult.auto_mapped} · กลับมาขาย {syncResult.revived} · ถูกลบจาก TSF{' '}
            {syncResult.removed} · ประมวลผลออเดอร์ค้าง {syncResult.reevaluated_orders} · รอคิว{' '}
            {syncResult.deferred}
          </p>
        </Card>
      ) : null}
      <FilterBar className="mt-4">
        <Button size="sm" aria-pressed={stockFilter === true} variant={stockFilter === true ? 'primary' : 'secondary'} onClick={() => { setStockFilter(stockFilter === true ? null : true); setOffset(0) }}>{labelTh('STOCK_CONTROL_FILTER_ON')}</Button>
        <Button size="sm" aria-pressed={stockFilter === false} variant={stockFilter === false ? 'primary' : 'secondary'} onClick={() => { setStockFilter(stockFilter === false ? null : false); setOffset(0) }}>{labelTh('STOCK_CONTROL_FILTER_OFF')}</Button>
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
      {notice ? <p role="status" className="mt-4">{notice}</p> : null}
      {canControl && selected.length > 0 ? <FilterBar className="mt-4">
        <span>เลือก {selected.length} รายการ</span>
        <Button disabled={busy} onClick={() => setBulkEnabled(true)}>{labelTh('STOCK_CONTROL_ON')}</Button>
        <Button disabled={busy} onClick={() => setBulkEnabled(false)}>{labelTh('STOCK_CONTROL_OFF')}</Button>
      </FilterBar> : null}
      {summary ? (
        <p role="status" className="mt-2 text-[13px] text-stone-600">
          ตรวจสอบอีกครั้ง: ปล่อยออเดอร์แล้ว {summary.released}, สต็อกไม่พอ {summary.out_of_stock}, ยังพักอยู่{' '}
          {summary.still_held}, รอดำเนินการภายหลัง {summary.deferred}
        </p>
      ) : null}
      {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : null}
      <div className="mt-4">
        <DataTable aria-label="รายการขายจากช่องทาง">
          <DataTableHead>
            <tr>
              {canControl ? <DataTableTh><Checkbox aria-label="เลือกทั้งหมดในหน้านี้" checked={items.length > 0 && selected.length === items.length} onCheckedChange={(checked) => setSelected(checked ? items.map(row => row.id) : [])} /></DataTableTh> : null}
              <DataTableTh>{labelTh('STOCK_CONTROL')}</DataTableTh>
              <DataTableTh>รหัสสินค้าจากช่องทาง</DataTableTh>
              <DataTableTh>รหัสสินค้าผู้ขาย</DataTableTh>
              <DataTableTh>ชื่อ</DataTableTh>
              <DataTableTh>จับคู่แล้ว</DataTableTh>
              <DataTableTh>ที่มา</DataTableTh>
              <DataTableTh>ออเดอร์ค้าง</DataTableTh>
              <DataTableTh />
            </tr>
          </DataTableHead>
          <tbody>
            {items.map((row) => (
              <DataTableRow key={row.id}>
                {canControl ? <DataTableCell><Checkbox aria-label={`เลือก ${row.external_sku_id}`} checked={selected.includes(row.id)} onCheckedChange={(checked) => setSelected(current => checked ? [...current, row.id] : current.filter(id => id !== row.id))} /></DataTableCell> : null}
                <DataTableCell>
                  {canControl ? <Button size="sm" aria-label={labelTh('STOCK_CONTROL')} aria-pressed={row.stock_control} disabled={busy || (!row.stock_control && (!row.sku_id || !!row.removed_at))} onClick={() => void changeControl(!row.stock_control, row.id)}>{labelTh(row.stock_control ? 'STOCK_CONTROL_ENABLED' : 'STOCK_CONTROL_DISABLED')}</Button>
                    : <Badge title={mode !== 'CONTROL' ? `แก้ไขได้เฉพาะโหมด${labelTh('CONTROL')}` : 'สิทธิ์ของคุณอ่านได้อย่างเดียว'}>{labelTh(row.stock_control ? 'STOCK_CONTROL_ENABLED' : 'STOCK_CONTROL_DISABLED')}</Badge>}
                </DataTableCell>
                <DataTableCell>
                  {row.external_sku_id}
                  {row.removed_at ? (
                    <Badge variant="muted" className="ml-1 normal-case" title="ถูกลบจากช่องทาง">
                      {' '}
                      ถูกลบแล้ว
                    </Badge>
                  ) : null}
                </DataTableCell>
                <DataTableCell>{row.seller_sku ?? '—'}</DataTableCell>
                <DataTableCell>{row.name ?? '—'}</DataTableCell>
                <DataTableCell>
                  {row.sku_id
                    ? `${row.sku_code ?? row.sku_id}${row.sku_name ? ` — ${row.sku_name}` : ''}`
                    : 'ยังไม่จับคู่'}
                </DataTableCell>
                <DataTableCell>{labelTh(row.mapping_source, 'mapping_source')}</DataTableCell>
                <DataTableCell>{row.held_orders}</DataTableCell>
                <DataTableCell>
                  {access.canWrite && !row.removed_at ? (
                    <div className="flex flex-wrap gap-2">
                      <Button type="button" size="sm" onClick={() => openMapPicker(row)}>
                        จับคู่
                      </Button>
                      {row.sku_id ? (
                        <Button type="button" size="sm" variant="ghost" onClick={() => setUnmapListing(row)}>
                          ยกเลิกการจับคู่
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
      <p className="mt-4 text-[13px] text-stone-600">{total} รายการขาย</p>
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
          ก่อนหน้า
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
          ถัดไป
        </Button>
      </div>
      <Dialog
        open={activeListing != null}
        onOpenChange={(open) => {
          if (!open) closeMapPicker()
        }}
        title={activeListing ? `จับคู่ ${activeListing.external_sku_id}` : 'จับคู่รายการขาย'}
      >
        <div className="space-y-4">
          <div>
            <Label htmlFor="picker-sku-search">ค้นหารหัสสินค้า</Label>
            <Input
              id="picker-sku-search"
              aria-label="ค้นหารหัสสินค้า"
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
          {pickerSku ? <p className="text-[13px] text-stone-600">เลือกแล้ว: {pickerSku.sku_code}</p> : null}
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="primary" disabled={busy || !pickerSku} onClick={() => void saveMapping()}>
              บันทึกการจับคู่
            </Button>
            <Button type="button" variant="secondary" onClick={() => closeMapPicker()}>
              ยกเลิก
            </Button>
          </div>
        </div>
      </Dialog>
      <Dialog open={bulkEnabled !== null && canControl} onOpenChange={(open) => { if (!open && !busy) setBulkEnabled(null) }} title={labelTh(bulkEnabled ? 'STOCK_CONTROL_ON' : 'STOCK_CONTROL_OFF')}>
        <p>ยืนยันการเปลี่ยนการคุมสต็อก {selected.length} รายการ</p>
        <Button disabled={busy} onClick={() => { if (bulkEnabled !== null) void changeControl(bulkEnabled) }}>ยืนยัน</Button>
        <Button disabled={busy} variant="secondary" onClick={() => setBulkEnabled(null)}>ยกเลิก</Button>
      </Dialog>
      <ConfirmationDialog open={unmapListing !== null} onOpenChange={(open) => { if (!open) setUnmapListing(null) }} danger
        title="ยกเลิกการจับคู่รหัสสินค้า" description={`ยกเลิกการจับคู่ ${unmapListing?.external_sku_id ?? ''}?`}
        onConfirm={() => { if (unmapListing) void unmap(unmapListing) }} />
    </PageContent>
  )
}
