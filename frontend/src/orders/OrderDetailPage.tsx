import {
  CircleDollarSign,
  Package,
  PauseCircle,
  Truck,
} from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import type { Me } from '../auth/AuthContext'
import { AlertBanner } from '../ui/Alert'
import { Button } from '../ui/Button'
import { Card, CardBody, CardHeader } from '../ui/Card'
import {
  DataTable,
  DataTableCell,
  DataTableHead,
  DataTableRow,
  DataTableTh,
} from '../ui/DataTable'
import { Input } from '../ui/Input'
import { Label } from '../ui/Label'
import { PageContent } from '../ui/PageContent'
import { StatusBadge } from '../ui/StatusBadge'
import { Timeline, type TimelineItem } from '../ui/Timeline'
import { formatBangkokDateTime, formatMoney } from '../ui/format'
import { holdReasonLabelTh } from '../ui/status'
import { ordersAccess, type OrdersAccess } from './access'
import { ordersApi, ordersMessage, type OrderDetail } from './api'

function lineSubtotal(order: OrderDetail): number {
  return order.lines.reduce((sum, line) => {
    const price = Number.parseFloat(line.unit_price || '0')
    return sum + (Number.isFinite(price) ? price * line.qty : 0)
  }, 0)
}

function moneyOrComputed(order: OrderDetail) {
  const computed = lineSubtotal(order)
  const subtotal = order.subtotal != null ? Number.parseFloat(order.subtotal) : computed
  const shipping =
    order.shipping_fee != null
      ? Number.parseFloat(order.shipping_fee)
      : Math.max(0, Number.parseFloat(order.grand_total) - subtotal)
  const discount =
    order.discount != null
      ? Number.parseFloat(order.discount)
      : Math.max(0, subtotal + shipping - Number.parseFloat(order.grand_total))
  return {
    subtotal: Number.isFinite(subtotal) ? subtotal : computed,
    shipping: Number.isFinite(shipping) ? shipping : 0,
    discount: Number.isFinite(discount) ? discount : 0,
    grand: Number.parseFloat(order.grand_total),
  }
}

function timelineIcon(dimension: string) {
  if (dimension.includes('payment')) return CircleDollarSign
  if (dimension.includes('fulfillment') || dimension.includes('ship')) return Truck
  if (dimension.includes('hold')) return PauseCircle
  return Package
}

export default function OrderDetailPage({ id, me }: { id: string; me: Me }) {
  const access: OrdersAccess = ordersAccess(me)
  const [order, setOrder] = useState<OrderDetail | null>(null)
  const [error, setError] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let active = true
    ordersApi
      .detail(id)
      .then((detail) => {
        if (active) {
          setOrder(detail)
          setError('')
        }
      })
      .catch((err: unknown) => {
        if (active) setError(ordersMessage(err, 'Could not load order'))
      })
    return () => {
      active = false
    }
  }, [id])

  async function holdRecheck() {
    if (!order) return
    const ok = window.confirm('Re-check hold and try to reserve stock again?')
    if (!ok) return
    setBusy(true)
    setError('')
    try {
      const key = `ui-${Date.now()}`
      await ordersApi.holdRecheck(order.id, key)
      const refreshed = await ordersApi.detail(id)
      setOrder(refreshed)
    } catch (err: unknown) {
      setError(ordersMessage(err, 'Re-check failed'))
    } finally {
      setBusy(false)
    }
  }

  async function requestCancel() {
    if (!order) return
    const ok = window.confirm('Send a cancel request to the sales channel?')
    if (!ok) return
    setBusy(true)
    setError('')
    try {
      await ordersApi.requestCancel(order.id, reason.trim())
      const refreshed = await ordersApi.detail(id)
      setOrder(refreshed)
    } catch (err: unknown) {
      setError(ordersMessage(err, 'Cancel request failed'))
    } finally {
      setBusy(false)
    }
  }

  const totals = useMemo(() => (order ? moneyOrComputed(order) : null), [order])

  const timelineItems: TimelineItem[] = useMemo(() => {
    if (!order) return []
    return order.timeline.map((entry, index) => {
      const Icon = timelineIcon(entry.dimension)
      return {
        id: `${entry.dimension}-${index}`,
        icon: Icon,
        title: `${entry.dimension}: ${entry.from_value} → ${entry.to_value}`,
        time: formatBangkokDateTime(entry.at),
        description: entry.reason ? `${entry.reason} · ${entry.actor}` : entry.actor,
      }
    })
  }, [order])

  if (!order) {
    return (
      <PageContent wide>
        <p role="status" className="text-[13px] text-stone-600">
          {error || 'Loading order…'}
        </p>
      </PageContent>
    )
  }

  const showCancel = access.canRequestCancel(order)
  const showRecheck = access.canHoldRecheck(order)
  const onHold = order.hold_reason !== 'NONE'

  return (
    <PageContent wide>
      <p className="mb-3">
        <a href="#/orders" className="text-[13px] font-medium text-brand-700 hover:underline">
          ← Orders
        </a>
      </p>

      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1
            aria-label={`Order ${order.external_order_id}`}
            className="font-mono text-[22px] font-semibold tracking-tight text-stone-900"
          >
            {order.external_order_id}
          </h1>
          <div className="mt-2 flex flex-wrap gap-2">
            <StatusBadge value={order.channel_account.channel} kind="channel" />
            <StatusBadge value={order.order_status} kind="order" />
            <StatusBadge value={order.fulfillment_status} kind="fulfillment" />
            <StatusBadge value={order.payment_status} kind="payment" />
            <StatusBadge value={order.payment_method} kind="payment_method" />
          </div>
          <p className="mt-2 text-[13px] text-stone-500">
            สั่งซื้อ {formatBangkokDateTime(order.ordered_at)} · {order.channel_account.channel} · v
            {order.version}
          </p>
        </div>
      </div>

      {onHold ? (
        <div role="status" className="mt-4">
        <AlertBanner
          variant="warning"
          title={
            <>
              Hold: {order.hold_reason}
              {order.hold_detail ? ` (${order.hold_detail})` : ''}
              {order.hold_note ? ` — ${order.hold_note}` : ''}
            </>
          }
          actions={
            <>
              {showRecheck ? (
                <Button type="button" variant="secondary" disabled={busy} onClick={() => void holdRecheck()}>
                  Re-check hold
                </Button>
              ) : null}
              {order.hold_reason === 'SKU_NOT_MAPPED' ? (
                <Button type="button" variant="primary" asChild>
                  <a
                    href={`#/channel/listings?channel_account_id=${encodeURIComponent(order.channel_account.id)}&mapped=false`}
                  >
                    จับคู่ SKU
                  </a>
                </Button>
              ) : null}
            </>
          }
          footer={
            order.ship_by ? (
              <span>Ship by {formatBangkokDateTime(order.ship_by)}</span>
            ) : null
          }
        >
          {holdReasonLabelTh(order.hold_reason)}
        </AlertBanner>
        </div>
      ) : null}

      <div className="mt-6 grid gap-4 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          <Card>
            <CardHeader title="รายการสินค้า" />
            <DataTable aria-label="Order lines" className="rounded-none border-0 shadow-none">
              <DataTableHead>
                <tr>
                  <DataTableTh>SKU</DataTableTh>
                  <DataTableTh>ชื่อ</DataTableTh>
                  <DataTableTh className="text-right">จำนวน</DataTableTh>
                  <DataTableTh className="text-right">ราคา</DataTableTh>
                  <DataTableTh className="text-right">รวม</DataTableTh>
                </tr>
              </DataTableHead>
              <tbody>
                {order.lines.map((line) => {
                  const unit = Number.parseFloat(line.unit_price || '0')
                  const lineTotal = Number.isFinite(unit) ? unit * line.qty : 0
                  return (
                    <DataTableRow key={line.id}>
                      <DataTableCell mono>
                        {line.sku_id ? (
                          <a
                            href={`#/catalog/skus/${line.sku_id}/history`}
                            className="text-brand-700 hover:underline"
                          >
                            {line.sku_code}
                          </a>
                        ) : (
                          line.external_sku_id
                        )}
                      </DataTableCell>
                      <DataTableCell>
                        {line.name}
                        {!line.mapped ? (
                          <div className="mt-1">
                            <a
                              className="text-[12px] text-brand-700 hover:underline"
                              href={`#/channel/listings?channel_account_id=${encodeURIComponent(order.channel_account.id)}&mapped=false&q=${encodeURIComponent(line.external_sku_id)}`}
                            >
                              Not mapped
                            </a>
                          </div>
                        ) : null}
                        {line.bundle && line.components.length > 0 ? (
                          <ul className="mt-1 list-disc pl-4 text-[12px] text-stone-600">
                            {line.components.map((c) => (
                              <li key={c.sku_id}>
                                <a href={`#/catalog/skus/${c.sku_id}/history`} className="text-brand-700">
                                  {c.sku_code}
                                </a>{' '}
                                × {c.qty}
                              </li>
                            ))}
                          </ul>
                        ) : null}
                      </DataTableCell>
                      <DataTableCell className="text-right tabular-nums">{line.qty}</DataTableCell>
                      <DataTableCell mono className="text-right">
                        {formatMoney(line.unit_price)}
                      </DataTableCell>
                      <DataTableCell mono className="text-right">
                        {formatMoney(lineTotal)}
                      </DataTableCell>
                    </DataTableRow>
                  )
                })}
              </tbody>
              {totals ? (
                <tfoot className="border-t border-stone-100 bg-stone-50/60 text-[13px]">
                  <tr>
                    <td colSpan={4} className="px-4 py-2 text-right text-stone-500">
                      Subtotal
                    </td>
                    <td className="px-4 py-2 text-right font-mono">{formatMoney(totals.subtotal)}</td>
                  </tr>
                  <tr>
                    <td colSpan={4} className="px-4 py-2 text-right text-stone-500">
                      Shipping
                    </td>
                    <td className="px-4 py-2 text-right font-mono">{formatMoney(totals.shipping)}</td>
                  </tr>
                  <tr>
                    <td colSpan={4} className="px-4 py-2 text-right text-stone-500">
                      Discount
                    </td>
                    <td className="px-4 py-2 text-right font-mono">−{formatMoney(totals.discount)}</td>
                  </tr>
                  <tr>
                    <td colSpan={4} className="px-4 py-1.5 text-right font-semibold text-stone-800">
                      Total
                    </td>
                    <td className="px-4 py-1.5 text-right font-mono font-semibold">
                      {formatMoney(totals.grand)}
                    </td>
                  </tr>
                </tfoot>
              ) : null}
            </DataTable>
          </Card>

          {order.reservations.length > 0 ? (
            <Card>
              <CardHeader title="Reservations" />
              <CardBody>
                <ul className="space-y-1 text-[13px]">
                  {order.reservations.map((r) => (
                    <li key={r.id}>
                      <a href={`#/catalog/skus/${r.sku_id}/history`} className="font-mono text-brand-700">
                        {r.sku_code}
                      </a>{' '}
                      · {r.qty} @ {r.warehouse_code} ({r.status})
                    </li>
                  ))}
                </ul>
              </CardBody>
            </Card>
          ) : null}

          {order.shipments.length > 0 ? (
            <Card>
              <CardHeader title="Shipments" />
              <CardBody>
                <ul className="space-y-1 text-[13px]">
                  {order.shipments.map((s) => (
                    <li key={s.id}>
                      {s.tracking_no ?? '—'} · {s.carrier ?? '—'} · {s.status}
                    </li>
                  ))}
                </ul>
              </CardBody>
            </Card>
          ) : null}
        </div>

        <div className="space-y-4">
          <Card>
            <CardHeader title="สถานะออเดอร์" />
            <CardBody>
              <dl className="space-y-3 text-[13px]">
                <div className="flex items-center justify-between gap-2">
                  <dt className="text-stone-500">Order</dt>
                  <dd>
                    <StatusBadge value={order.order_status} kind="order" />
                  </dd>
                </div>
                <div className="flex items-center justify-between gap-2">
                  <dt className="text-stone-500">Payment</dt>
                  <dd>
                    <StatusBadge value={order.payment_status} kind="payment" />
                  </dd>
                </div>
                <div className="flex items-center justify-between gap-2">
                  <dt className="text-stone-500">Fulfillment</dt>
                  <dd>{order.fulfillment_status}</dd>
                </div>
              </dl>
            </CardBody>
          </Card>

          <Card>
            <CardHeader title="การชำระเงิน" />
            <CardBody>
              <dl className="space-y-2 text-[13px]">
                <div className="flex justify-between gap-2">
                  <dt className="text-stone-500">Method</dt>
                  <dd>
                    <StatusBadge value={order.payment_method} kind="payment_method" />
                  </dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt className="text-stone-500">Status</dt>
                  <dd>
                    <StatusBadge value={order.payment_status} kind="payment" />
                  </dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt className="text-stone-500">Paid at</dt>
                  <dd className="tabular-nums">{formatBangkokDateTime(order.paid_at)}</dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt className="text-stone-500">Total</dt>
                  <dd className="font-mono">{formatMoney(order.grand_total)}</dd>
                </div>
              </dl>
            </CardBody>
          </Card>

          <Card>
            <CardHeader title="การจัดส่ง" />
            <CardBody>
              <p className="text-[13px] text-stone-700">
                {order.recipient.name_masked ?? '—'}
                <br />
                {order.recipient.phone_masked ?? '—'}
                <br />
                {order.recipient.province} {order.recipient.postcode}
              </p>
              {order.ship_by ? (
                <p className="mt-2 text-[12px] text-stone-500">
                  Ship by {formatBangkokDateTime(order.ship_by)}
                </p>
              ) : null}
            </CardBody>
          </Card>

          <Card>
            <CardHeader title="Timeline" />
            <Timeline items={timelineItems} aria-label="Status timeline" />
          </Card>
        </div>
      </div>

      {showRecheck && order.hold_reason === 'NONE' ? (
        <section className="mt-4" aria-label="Hold recheck actions">
          <Button type="button" variant="secondary" disabled={busy} onClick={() => void holdRecheck()}>
            Re-check hold
          </Button>
        </section>
      ) : null}

      {showCancel ? (
        <Card className="mt-6">
          <CardHeader title="Request cancel" />
          <CardBody>
            <section aria-label="Request cancel" className="space-y-3">
              <div>
                <Label htmlFor="cancel-reason">Reason</Label>
                <Input id="cancel-reason" value={reason} onChange={(e) => setReason(e.target.value)} required />
              </div>
              <Button
                type="button"
                variant="danger"
                disabled={busy || reason.trim().length === 0}
                onClick={() => void requestCancel()}
              >
                Request cancel
              </Button>
            </section>
          </CardBody>
        </Card>
      ) : null}
      {error ? (
        <p role="alert" className="mt-4 text-[13px] text-red-700">
          {error}
        </p>
      ) : null}
    </PageContent>
  )
}
