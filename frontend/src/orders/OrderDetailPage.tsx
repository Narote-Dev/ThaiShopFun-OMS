import { useEffect, useState } from 'react'
import type { Me } from '../auth/AuthContext'
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
      <main>
        <p role="status">{error || 'Loading order…'}</p>
      </main>
    )
  }

  const showCancel = access.canRequestCancel(order)
  return (
    <main className="wide">
      <p><a href="#/orders">← Orders</a></p>
      <h1>Order {order.external_order_id}</h1>
      {order.hold_reason !== 'NONE' ? (
        <p role="status" className="banner">
          Hold: {order.hold_reason}
          {order.hold_note ? ` — ${order.hold_note}` : ''}
        </p>
      ) : null}
      <dl className="status-grid">
        <div>
          <dt>Order</dt>
          <dd>{order.order_status}</dd>
        </div>
        <div>
          <dt>Payment</dt>
          <dd>{order.payment_status}</dd>
        </div>
        <div>
          <dt>Fulfillment</dt>
          <dd>{order.fulfillment_status}</dd>
        </div>
      </dl>
      <section>
        <h2>Recipient</h2>
        <p>
          {order.recipient.name_masked ?? '—'} · {order.recipient.phone_masked ?? '—'} ·{' '}
          {order.recipient.province} {order.recipient.postcode}
        </p>
      </section>
      <section>
        <h2>Lines</h2>
        <table aria-label="Order lines">
          <thead>
            <tr>
              <th>SKU</th>
              <th>Name</th>
              <th>Qty</th>
              <th>Mapping</th>
            </tr>
          </thead>
          <tbody>
            {order.lines.map((line) => (
              <tr key={line.id}>
                <td>
                  {line.sku_id ? (
                    <a href={`#/catalog/skus/${line.sku_id}/history`}>{line.sku_code}</a>
                  ) : (
                    line.external_sku_id
                  )}
                </td>
                <td>{line.name}</td>
                <td>{line.qty}</td>
                <td>{line.mapped ? 'Mapped' : 'Not mapped'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      <section>
        <h2>Timeline</h2>
        <ol aria-label="Status timeline">
          {order.timeline.map((entry, index) => (
            <li key={`${entry.dimension}-${index}`}>
              {entry.at}: {entry.dimension} {entry.from_value} → {entry.to_value} ({entry.actor})
            </li>
          ))}
        </ol>
      </section>
      {showCancel ? (
        <section aria-label="Request cancel">
          <h2>Request cancel</h2>
          <label>
            Reason
            <input value={reason} onChange={(e) => setReason(e.target.value)} />
          </label>
          <button type="button" disabled={busy} onClick={() => void requestCancel()}>
            Request cancel
          </button>
        </section>
      ) : null}
      {error ? <p role="alert">{error}</p> : null}
    </main>
  )
}
