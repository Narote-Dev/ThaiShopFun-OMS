import { PackageCheck, PauseCircle, Plus, RefreshCw, ShoppingBag, TriangleAlert, Upload } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { fetchAllSkus } from '../catalog/listSkus'
import { ordersApi, ordersMessage, type HoldGroup, type OrderListItem } from '../orders/api'
import { fetchAllOrders } from '../orders/listOrders'
import { emptyOrderFilters } from '../orders/orderViews'
import { Button } from '../ui/Button'
import { ComingSoonButton } from '../ui/ComingSoon'
import { Card, CardHeader, KpiCard } from '../ui/Card'
import { DataTable, DataTableCell, DataTableHead, DataTableRow, DataTableTh } from '../ui/DataTable'
import { OrderDisplayBadge } from '../ui/OrderDisplayBadge'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'
import { StatusBadge } from '../ui/StatusBadge'
import { bangkokTodayIso, formatMoney } from '../ui/format'

export const LOW_STOCK_THRESHOLD = 25

export default function DashboardPage({ name }: { name: string }) {
  const [orders, setOrders] = useState<OrderListItem[]>([])
  const [orderTotal, setOrderTotal] = useState(0)
  const [holds, setHolds] = useState<HoldGroup[]>([])
  const [lowStockCount, setLowStockCount] = useState(0)
  const [lowStockPreview, setLowStockPreview] = useState<
    { id: string; sku_code: string; name: string; available: number }[]
  >([])
  const [error, setError] = useState('')

  const today = bangkokTodayIso()

  const dateLabel = useMemo(() => {
    try {
      return new Intl.DateTimeFormat('th-TH', {
        timeZone: 'Asia/Bangkok',
        weekday: 'long',
        day: 'numeric',
        month: 'short',
        year: 'numeric',
      }).format(new Date())
    } catch {
      return today
    }
  }, [today])

  useEffect(() => {
    let active = true
    const filters = emptyOrderFilters(today)
    void Promise.all([fetchAllOrders(filters), ordersApi.holds(), fetchAllSkus()])
      .then(([orderResult, holdBody, skus]) => {
        if (!active) return
        setOrders(orderResult.items)
        setOrderTotal(orderResult.total)
        setHolds(holdBody.groups)
        const low = skus
          .filter((s) => !s.is_bundle)
          .map((s) => ({
            id: s.id,
            sku_code: s.sku_code,
            name: s.name,
            available: (s.on_hand ?? 0) - (s.reserved ?? 0),
          }))
          .filter((s) => s.available <= LOW_STOCK_THRESHOLD)
          .sort((a, b) => a.available - b.available)
        setLowStockCount(low.length)
        setLowStockPreview(low.slice(0, 8))
        setError('')
      })
      .catch((err: unknown) => {
        if (active) setError(ordersMessage(err, 'Could not load dashboard'))
      })
    return () => {
      active = false
    }
  }, [today])

  const activeOrders = useMemo(
    () => orders.filter((o) => o.order_status !== 'CANCELLED'),
    [orders],
  )
  const readyCount = useMemo(
    () => activeOrders.filter((o) => o.fulfillment_status === 'READY_TO_PICK').length,
    [activeOrders],
  )
  const holdCount = useMemo(() => holds.reduce((sum, g) => sum + g.count, 0), [holds])
  const oosHolds = useMemo(
    () => holds.filter((g) => g.hold_reason === 'OUT_OF_STOCK').reduce((s, g) => s + g.count, 0),
    [holds],
  )
  const unmappedHolds = useMemo(
    () => holds.filter((g) => g.hold_reason === 'SKU_NOT_MAPPED').reduce((s, g) => s + g.count, 0),
    [holds],
  )
  const salesTotal = useMemo(
    () => activeOrders.reduce((sum, o) => sum + Number.parseFloat(o.grand_total || '0'), 0),
    [activeOrders],
  )
  const cancelled = useMemo(() => orders.filter((o) => o.order_status === 'CANCELLED').length, [orders])

  const holdBar =
    holdCount > 0
      ? {
          oos: Math.round((oosHolds / holdCount) * 100),
          unmapped: Math.round((unmappedHolds / holdCount) * 100),
        }
      : { oos: 0, unmapped: 0 }

  return (
    <PageContent>
      <PageHeader
        title={<span>สวัสดี 👋</span>}
        subtitle={
          <>
            ภาพรวมร้าน <span className="font-medium text-stone-700">{name}</span> · {dateLabel}
          </>
        }
        actions={
          <>
            <ComingSoonButton>
              <RefreshCw className="h-4 w-4" />
              ซิงก์ออเดอร์
            </ComingSoonButton>
            <Button type="button" variant="secondary" asChild>
              <a href="#/catalog/import">
                <Upload className="h-4 w-4" />
                นำเข้าสินค้า
              </a>
            </Button>
            <Button type="button" variant="primary" asChild>
              <a href="#/stock/documents">
                <Plus className="h-4 w-4" />
                สร้างเอกสารสต็อก
              </a>
            </Button>
          </>
        }
      />
      <h1 className="sr-only">{name}</h1>
      {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : null}

      <div className="mt-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          label="ออเดอร์วันนี้"
          value={orderTotal}
          hint={
            <>
              ยอดขาย <span className="font-medium text-stone-700">{formatMoney(salesTotal, { decimals: 0 })}</span> ·
              ยกเลิก {cancelled}
            </>
          }
          icon={<ShoppingBag className="h-4 w-4" />}
          iconClassName="bg-brand-50 text-brand-600"
        />
        <KpiCard
          label="รอหยิบ"
          value={readyCount}
          hint="สถานะ READY_TO_PICK พร้อมแพ็ก"
          icon={<PackageCheck className="h-4 w-4" />}
          iconClassName="bg-emerald-50 text-emerald-600"
        />
        <KpiCard
          label="ออเดอร์ค้าง"
          value={holdCount}
          hint={
            holdCount > 0 ? (
              <div>
                <div className="flex h-1.5 overflow-hidden rounded-full bg-stone-100">
                  <div className="bg-red-500" style={{ width: `${holdBar.oos}%` }} />
                  <div className="bg-amber-400" style={{ width: `${holdBar.unmapped}%` }} />
                </div>
                <div className="mt-2 flex gap-3 text-[11.5px] text-stone-600">
                  <span className="flex items-center gap-1">
                    <span className="h-2 w-2 rounded-sm bg-red-500" />
                    สต็อกไม่พอ {oosHolds}
                  </span>
                  <span className="flex items-center gap-1">
                    <span className="h-2 w-2 rounded-sm bg-amber-400" />
                    SKU ไม่จับคู่ {unmappedHolds}
                  </span>
                </div>
              </div>
            ) : (
              'ไม่มีออเดอร์ค้าง'
            )
          }
          icon={<PauseCircle className="h-4 w-4" />}
          iconClassName="bg-red-50 text-red-600"
        />
        <KpiCard
          label="สต็อกใกล้หมด"
          value={lowStockCount}
          hint={`SKU คงเหลือพร้อมขาย ≤ ${LOW_STOCK_THRESHOLD} ชิ้น`}
          icon={<TriangleAlert className="h-4 w-4" />}
          iconClassName="bg-amber-50 text-amber-600"
        />
      </div>

      <div className="mt-5 grid items-start gap-4 lg:grid-cols-5">
        <Card className="lg:col-span-3">
          <CardHeader
            title="คิวออเดอร์ค้าง"
            description="ออเดอร์ที่ต้องแก้ก่อนจัดส่งได้"
            action={
              <a
                href="#/orders/holds"
                className="inline-flex items-center gap-1 text-[12.5px] font-medium text-stone-600 hover:text-brand-700"
              >
                ดูทั้งหมด →
              </a>
            }
          />
          <ul className="divide-y divide-stone-100">
            {holds.length === 0 ? (
              <li className="px-5 py-6 text-[13px] text-stone-500">ไม่มีออเดอร์ค้าง</li>
            ) : (
              holds.flatMap((group) =>
                group.samples.slice(0, 2).map((sample) => (
                  <li key={`${group.hold_reason}-${sample.id}`} className="flex items-center gap-3 px-5 py-3">
                    <span
                      className={`grid h-9 w-9 shrink-0 place-items-center rounded-lg ${
                        group.hold_reason === 'SKU_NOT_MAPPED'
                          ? 'bg-amber-50 text-amber-600'
                          : 'bg-red-50 text-red-600'
                      }`}
                    >
                      <PauseCircle className="h-4 w-4" />
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2 text-[13px] font-medium">
                        <StatusBadge value={group.hold_reason} kind="hold_reason" />
                        <span className="rounded-full bg-stone-100 px-1.5 text-[11px] font-medium text-stone-600 tabular-nums">
                          {group.count}
                        </span>
                      </div>
                      <div className="mt-0.5 text-[11.5px] text-stone-500">
                        {group.hold_detail ?? '—'} ·{' '}
                        <a
                          href={`#/orders/${sample.id}`}
                          className="font-mono text-stone-700 underline decoration-stone-300 underline-offset-2 hover:text-brand-700"
                        >
                          {sample.external_order_id}
                        </a>
                      </div>
                    </div>
                    <a
                      href={`#/orders/${sample.id}`}
                      className="rounded-md px-2 py-1 text-[12.5px] font-medium text-brand-700 hover:bg-brand-50"
                    >
                      เปิดออเดอร์
                    </a>
                  </li>
                )),
              )
            )}
          </ul>
        </Card>
        <Card className="lg:col-span-2">
          <CardHeader
            title="สต็อกใกล้หมด"
            action={
              <a
                href="#/catalog/skus"
                aria-label="Open SKU list"
                className="text-[12.5px] font-medium text-stone-600 hover:text-brand-700"
              >
                SKUs
              </a>
            }
          />
          <ul className="divide-y divide-stone-100">
            {lowStockPreview.length === 0 ? (
              <li className="px-5 py-6 text-[13px] text-stone-500">ไม่มี SKU ใกล้หมด</li>
            ) : (
              lowStockPreview.map((sku) => {
                const pct = Math.min(100, Math.round((Math.max(0, sku.available) / LOW_STOCK_THRESHOLD) * 100))
                return (
                  <li key={sku.id} className="px-5 py-3">
                    <div className="flex items-center justify-between gap-2">
                      <div className="min-w-0">
                        <div className="truncate text-[13px] font-medium">{sku.name}</div>
                        <div className="font-mono text-[11.5px] text-stone-500">{sku.sku_code}</div>
                      </div>
                      <span
                        className={`rounded-full px-2 py-0.5 text-[11px] font-semibold ${
                          sku.available <= 0 ? 'bg-red-50 text-red-700' : 'bg-amber-50 text-amber-800'
                        }`}
                      >
                        {sku.available <= 0 ? 'หมด' : 'ใกล้หมด'}
                      </span>
                    </div>
                    <div className="mt-2 flex h-1.5 overflow-hidden rounded-full bg-stone-100">
                      <div
                        className={sku.available <= 0 ? 'bg-red-500' : 'bg-amber-400'}
                        style={{ width: `${pct}%` }}
                      />
                    </div>
                    <div className="mt-1 text-[11.5px] text-stone-500 tabular-nums">คงเหลือ {sku.available}</div>
                  </li>
                )
              })
            )}
          </ul>
        </Card>
      </div>

      <Card className="mt-5">
        <CardHeader
          title="ออเดอร์ล่าสุด"
          action={
            <a href="#/orders" className="text-[12.5px] font-medium text-stone-600 hover:text-brand-700">
              ไปที่ออเดอร์
            </a>
          }
        />
        <DataTable aria-label="Latest orders" className="border-0 shadow-none">
          <DataTableHead>
            <tr>
              <DataTableTh>Order</DataTableTh>
              <DataTableTh>Channel</DataTableTh>
              <DataTableTh>Payment</DataTableTh>
              <DataTableTh>Total</DataTableTh>
              <DataTableTh>Status</DataTableTh>
            </tr>
          </DataTableHead>
          <tbody>
            {orders.slice(0, 8).map((row) => (
              <DataTableRow key={row.id}>
                <DataTableCell mono>
                  <a href={`#/orders/${row.id}`} className="text-brand-700 hover:underline">
                    {row.external_order_id}
                  </a>
                </DataTableCell>
                <DataTableCell>
                  <StatusBadge value={row.channel} kind="channel" />
                </DataTableCell>
                <DataTableCell>
                  <StatusBadge value={row.payment_method} kind="payment_method" />
                </DataTableCell>
                <DataTableCell mono className="text-right">
                  {formatMoney(row.grand_total)}
                </DataTableCell>
                <DataTableCell>
                  <OrderDisplayBadge
                    order_status={row.order_status}
                    fulfillment_status={row.fulfillment_status}
                    hold_reason={row.hold_reason}
                  />
                </DataTableCell>
              </DataTableRow>
            ))}
          </tbody>
        </DataTable>
      </Card>
    </PageContent>
  )
}
