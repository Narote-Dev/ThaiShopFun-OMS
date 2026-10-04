import { useEffect, useState } from 'react'
import type { Me } from '../auth/AuthContext'
import { AlertBanner } from '../ui/Alert'
import { Button } from '../ui/Button'
import { Card, CardBody, CardHeader } from '../ui/Card'
import { DataTable, DataTableCell, DataTableHead, DataTableRow, DataTableTh } from '../ui/DataTable'
import { Input } from '../ui/Input'
import { Label } from '../ui/Label'
import { PageContent } from '../ui/PageContent'
import { StatusBadge } from '../ui/StatusBadge'
import { ordersAccess, type OrdersAccess } from './access'
import { ordersApi, ordersMessage, type OrderDetail } from './api'

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

  if (!order) {
    return (
      <PageContent wide>
        <p role="status" className="text-[13px] text-stone-600">{error || 'Loading order…'}</p>
      </PageContent>
    )
  }

  const showCancel = access.canRequestCancel(order)
  const showRecheck = access.canHoldRecheck(order)
  return (
    <PageContent wide>
      <p className="mb-3">
        <a href="#/orders" className="text-[13px] font-medium text-brand-700 hover:underline">← Orders</a>
      </p>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-[22px] font-semibold tracking-tight">Order {order.external_order_id}</h1>
          <div className="mt-2 flex flex-wrap gap-2">
            <StatusBadge value={order.channel_account.channel} kind="channel" />
            <StatusBadge value={order.order_status} kind="order" />
            <StatusBadge value={order.fulfillment_status} kind="fulfillment" />
            {order.hold_reason !== 'NONE' ? <StatusBadge value="ANY" kind="hold" /> : null}
            <StatusBadge value={order.payment_status} kind="payment" />
          </div>
          <p className="mt-2 text-[13px] text-stone-500">
            สั่งซื้อ {order.ordered_at} · {order.channel_account.channel} account · version {order.version}
          </p>
        </div>
      </div>

      {order.hold_reason !== 'NONE' ? (
        <div className="mt-4 space-y-3">
          <p
            role="status"
            className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-2 text-[13px] text-amber-950"
          >
            Hold: {order.hold_reason}
            {order.hold_detail ? ` (${order.hold_detail})` : ''}
            {order.hold_note ? ` — ${order.hold_note}` : ''}
          </p>
          <AlertBanner
            variant="warning"
            title={
              <span className="flex flex-wrap items-center gap-2">
                ออเดอร์ถูกพัก
                <StatusBadge value={order.hold_reason} kind="hold" />
              </span>
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
                      Map SKU
                    </a>
                  </Button>
                ) : null}
              </>
            }
          >
            {order.hold_detail ? order.hold_detail : null}
            {order.hold_note ? ` — ${order.hold_note}` : null}
          </AlertBanner>
        </div>
      ) : null}

      <div className="mt-6 grid gap-4 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          <Card>
            <CardHeader title="Lines" />
            <DataTable aria-label="Order lines" className="border-0 shadow-none">
              <DataTableHead>
                <tr>
                  <DataTableTh>SKU</DataTableTh>
                  <DataTableTh>Name</DataTableTh>
                  <DataTableTh>Qty</DataTableTh>
                  <DataTableTh>Mapping</DataTableTh>
                </tr>
              </DataTableHead>
              <tbody>
                {order.lines.map((line) => (
                  <DataTableRow key={line.id}>
                    <DataTableCell mono>
                      {line.sku_id ? (
                        <a href={`#/catalog/skus/${line.sku_id}/history`} className="text-brand-700 hover:underline">
                          {line.sku_code}
                        </a>
                      ) : (
                        line.external_sku_id
                      )}
                    </DataTableCell>
                    <DataTableCell>{line.name}</DataTableCell>
                    <DataTableCell>{line.qty}</DataTableCell>
                    <DataTableCell>
                      {line.mapped ? (
                        'Mapped'
                      ) : (
                        <a
                          className="text-brand-700 hover:underline"
                          href={`#/channel/listings?channel_account_id=${encodeURIComponent(order.channel_account.id)}&mapped=false&q=${encodeURIComponent(line.external_sku_id)}`}
                        >
                          Not mapped
                        </a>
                      )}
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
                  </DataTableRow>
                ))}
              </tbody>
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
                <div className="flex justify-between gap-2">
                  <dt className="text-stone-500">Order</dt>
                  <dd>{order.order_status}</dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt className="text-stone-500">Payment</dt>
                  <dd>{order.payment_status}</dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt className="text-stone-500">Fulfillment</dt>
                  <dd>{order.fulfillment_status}</dd>
                </div>
              </dl>
            </CardBody>
          </Card>

          <Card>
            <CardHeader title="Recipient" />
            <CardBody>
              <p className="text-[13px] text-stone-700">
                {order.recipient.name_masked ?? '—'} · {order.recipient.phone_masked ?? '—'} ·{' '}
                {order.recipient.province} {order.recipient.postcode}
              </p>
            </CardBody>
          </Card>

          <Card>
            <CardHeader title="Timeline" />
            <CardBody>
              <ol aria-label="Status timeline" className="space-y-2 text-[12.5px] text-stone-700">
                {order.timeline.map((entry, index) => (
                  <li key={`${entry.dimension}-${index}`}>
                    {entry.at}: {entry.dimension} {entry.from_value} → {entry.to_value} ({entry.actor})
                  </li>
                ))}
              </ol>
            </CardBody>
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
      {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : null}
    </PageContent>
  )
}
