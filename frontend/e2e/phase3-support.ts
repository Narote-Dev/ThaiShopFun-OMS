import { expect, type APIRequestContext } from '@playwright/test'

export const OMS = process.env.OMS_BASE_URL ?? 'http://127.0.0.1:8080'
export const MOCK = process.env.MOCK_BASE_URL ?? 'http://127.0.0.1:8090'

export async function ownerToken(request: APIRequestContext): Promise<string> {
  const response = await request.post(`${MOCK}/control/user-token`, {
    headers: { 'Content-Type': 'application/json' },
    data: { login_hint: 'owner-active' },
  })
  expect(response.ok()).toBeTruthy()
  return (await response.json()).access_token as string
}

export async function seedDemoCatalog(request: APIRequestContext): Promise<void> {
  await expect
    .poll(
      async () => {
        const catalog = await request.post(`${OMS}/control/demo/order-catalog`)
        if (!catalog.ok()) return ''
        const body = await catalog.json()
        return body.status === 'OK' ? 'ok' : ''
      },
      { timeout: 120_000, intervals: [2000] },
    )
    .toBe('ok')
}

export async function assertInvariants(request: APIRequestContext): Promise<void> {
  const response = await request.post(`${OMS}/control/demo/invariants-check`)
  expect(response.ok()).toBeTruthy()
  const body = await response.json()
  expect(body.status, `violations=${body.violations} checkFailed=${body.checkFailed}`).toBe('OK')
  expect(body.violations).toBe(0)
  expect(body.checkFailed ?? 0).toBe(0)
}

type OrderLine = { line_id: string; listing_sku_id: string; qty: number; unit_price?: number }

export async function createPrepaidOrder(
  request: APIRequestContext,
  externalOrderId: string,
  lines: OrderLine[],
  totals?: { subtotal: number; shipping_fee: number; discount: number; grand_total: number },
): Promise<void> {
  await expect
    .poll(
      async () => {
        const checkoutId = `e2e-co-${externalOrderId}`
        const reservationResponse = await request.post(`${MOCK}/control/checkout/reservations`, {
          data: {
            checkout_id: checkoutId,
            tsf_shop_id: 'shop_active',
            items: lines.map((line) => ({ listing_sku_id: line.listing_sku_id, qty: line.qty })),
          },
        })
        if (!reservationResponse.ok()) return ''
        const reservationPayload = await reservationResponse.json()
        if (reservationPayload.oms_status !== 201) return ''
        const reservationBody = JSON.parse(reservationPayload.oms_body as string) as {
          reservation_id: string
        }

        await request.post(`${MOCK}/control/orders/register`, {
          data: { shop_id: 'shop_active', order_id: externalOrderId, status: 'ACTIVE' },
        })

        const now = new Date().toISOString()
        const money = totals ?? { subtotal: 590, shipping_fee: 40, discount: 50, grand_total: 580 }
        const created = {
          event: {
            event_id: `e2e-created-${externalOrderId}`,
            event_type: 'order.created',
            schema_version: 1,
            occurred_at: now,
            tsf_shop_id: 'shop_active',
            aggregate_id: externalOrderId,
            aggregate_version: 1,
            data: {
              order_id: externalOrderId,
              reservation_id: reservationBody.reservation_id,
              payment_method: 'PREPAID',
              payment_expires_at: new Date(Date.now() + 3_600_000).toISOString(),
              currency: 'THB',
              totals: money,
              recipient: {
                name: 'E2E Buyer',
                phone: '0812345678',
                address: {
                  line1: '1 E2E Road',
                  district: 'Chatuchak',
                  province: 'Bangkok',
                  postcode: '10900',
                },
              },
              ship_by: new Date(Date.now() + 86_400_000).toISOString(),
              lines: lines.map((line) => ({
                line_id: line.line_id,
                listing_sku_id: line.listing_sku_id,
                seller_sku: 'DEMO-SKU-READY',
                name: 'E2E item',
                qty: line.qty,
                unit_price: line.unit_price ?? 295,
              })),
            },
          },
        }
        const createdResponse = await request.post(`${MOCK}/control/events/send`, { data: created })
        if (!createdResponse.ok()) return ''

        const paid = {
          event: {
            event_id: `e2e-paid-${externalOrderId}`,
            event_type: 'order.paid',
            schema_version: 1,
            occurred_at: now,
            tsf_shop_id: 'shop_active',
            aggregate_id: externalOrderId,
            aggregate_version: 2,
            data: { order_id: externalOrderId, payment_method: 'PREPAID' },
          },
        }
        const paidResponse = await request.post(`${MOCK}/control/events/send`, { data: paid })
        return paidResponse.ok() ? 'ok' : ''
      },
      { timeout: 120_000, intervals: [2000] },
    )
    .toBe('ok')
}

export async function waitForOrder(
  request: APIRequestContext,
  token: string,
  externalOrderId: string,
  fulfillment: string,
): Promise<{ id: string }> {
  let orderId = ''
  await expect
    .poll(
      async () => {
        const list = await request.get(
          `${OMS}/api/v1/orders?q=${encodeURIComponent(externalOrderId)}&limit=5`,
          { headers: { Authorization: `Bearer ${token}` } },
        )
        if (!list.ok()) return ''
        const items = (await list.json()).items as Array<{
          id: string
          external_order_id: string
          fulfillment_status: string
        }>
        const row = items.find((item) => item.external_order_id === externalOrderId)
        if (!row || row.fulfillment_status !== fulfillment) return ''
        orderId = row.id
        return fulfillment
      },
      { timeout: 120_000, intervals: [1500] },
    )
    .toBe(fulfillment)
  return { id: orderId }
}

export async function sendShipmentDelivered(
  request: APIRequestContext,
  externalOrderId: string,
  trackingNo: string,
): Promise<void> {
  const body = {
    event: {
      event_id: `e2e-ship-del-${externalOrderId}`,
      event_type: 'shipment.status_changed',
      schema_version: 1,
      occurred_at: new Date().toISOString(),
      tsf_shop_id: 'shop_active',
      aggregate_id: externalOrderId,
      aggregate_version: 3,
      data: { order_id: externalOrderId, status: 'DELIVERED', tracking_no: trackingNo },
    },
  }
  const response = await request.post(`${MOCK}/control/events/send`, { data: body })
  expect(response.ok()).toBeTruthy()
}

export async function sendReturnRequested(
  request: APIRequestContext,
  externalOrderId: string,
  returnId: string,
  lineId: string,
  qty: number,
): Promise<void> {
  const response = await request.post(`${MOCK}/control/returns/${externalOrderId}/request`, {
    data: {
      return_id: returnId,
      type: 'RETURN',
      reason: 'E2E partial return',
      lines: [{ line_id: lineId, qty }],
    },
  })
  expect(response.ok()).toBeTruthy()
}

export async function sendReturnDecided(
  request: APIRequestContext,
  externalOrderId: string,
  returnId: string,
): Promise<void> {
  const response = await request.post(`${MOCK}/control/returns/${externalOrderId}/decide`, {
    data: { return_id: returnId, decision: 'APPROVED' },
  })
  expect(response.ok()).toBeTruthy()
}

export async function sendRefundSucceeded(
  request: APIRequestContext,
  externalOrderId: string,
  refundId: string,
  returnId: string,
  amount: number,
): Promise<void> {
  const body = {
    event: {
      event_id: `e2e-refund-${refundId}`,
      event_type: 'refund.status_changed',
      schema_version: 1,
      occurred_at: new Date().toISOString(),
      tsf_shop_id: 'shop_active',
      aggregate_id: refundId,
      aggregate_version: 1,
      data: {
        order_id: externalOrderId,
        refund_id: refundId,
        return_id: returnId,
        status: 'SUCCEEDED',
        amount,
        currency: 'THB',
      },
    },
  }
  const response = await request.post(`${MOCK}/control/events/send`, { data: body })
  expect(response.ok()).toBeTruthy()
}

export async function driveOrderCancelled(
  request: APIRequestContext,
  token: string,
  externalOrderId: string,
  orderId: string,
): Promise<void> {
  let attempt = 0
  await expect
    .poll(
      async () => {
        const detail = await orderDetail(request, token, orderId)
        if (detail.order_status === 'CANCELLED') return 'cancelled'
        attempt += 1
        const body = {
          event: {
            event_id: `e2e-cancel-${externalOrderId}-${attempt}`,
            event_type: 'order.cancelled',
            schema_version: 1,
            occurred_at: new Date().toISOString(),
            tsf_shop_id: 'shop_active',
            aggregate_id: externalOrderId,
            aggregate_version: 3,
            data: { order_id: externalOrderId, reason: 'E2E_CANCEL' },
          },
        }
        await request.post(`${MOCK}/control/events/send`, { data: body })
        return ''
      },
      { timeout: 90_000, intervals: [2000] },
    )
    .toBe('cancelled')
}

export async function waitForPaymentStatus(
  request: APIRequestContext,
  token: string,
  orderId: string,
  status: string,
): Promise<void> {
  await expect
    .poll(
      async () => {
        const detail = await orderDetail(request, token, orderId)
        return detail.payment_status === status ? status : ''
      },
      { timeout: 90_000, intervals: [2000] },
    )
    .toBe(status)
}

export async function waitForReturnApproved(
  request: APIRequestContext,
  token: string,
  orderId: string,
): Promise<string> {
  let returnId = ''
  await expect
    .poll(
      async () => {
        const detail = await orderDetail(request, token, orderId)
        const returns = detail.returns as Array<{ id: string; status: string }> | undefined
        const row = returns?.find((item) => item.status === 'APPROVED')
        if (!row) return ''
        returnId = row.id
        return 'approved'
      },
      { timeout: 90_000, intervals: [2000] },
    )
    .toBe('approved')
  return returnId
}

export async function receiveReturnResellable(
  request: APIRequestContext,
  token: string,
  returnId: string,
): Promise<void> {
  const headers = { Authorization: `Bearer ${token}` }
  const detail = await request.get(`${OMS}/api/v1/returns/${returnId}`, { headers })
  expect(detail.ok()).toBeTruthy()
  const line = ((await detail.json()) as { lines: Array<{ return_line_id: string; qty: number }> }).lines[0]
  const response = await request.post(`${OMS}/api/v1/returns/${returnId}/receive`, {
    headers: { ...headers, 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    data: { lines: [{ return_line_id: line.return_line_id, condition: 'RESELLABLE', qty: 1 }] },
  })
  expect(response.ok()).toBeTruthy()
}

export async function orderDetail(
  request: APIRequestContext,
  token: string,
  orderId: string,
): Promise<Record<string, unknown>> {
  const response = await request.get(`${OMS}/api/v1/orders/${orderId}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(response.ok()).toBeTruthy()
  return (await response.json()) as Record<string, unknown>
}
