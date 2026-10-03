import { apiRequest, ApiError } from '../api/client'
import type { Page } from '../catalog/api'

export type OrderListItem = {
  id: string
  external_order_id: string
  order_status: string
  payment_status: string
  fulfillment_status: string
  hold_reason: string
  payment_method: string
  grand_total: string
  ordered_at: string
  ship_by: string | null
  channel_account_id: string
  channel: string
  phone_masked: string | null
}

export type TimelineEntry = {
  dimension: string
  from_value: string
  to_value: string
  reason: string | null
  actor: string
  at: string
}

export type OrderLine = {
  id: string
  external_line_id: string
  external_sku_id: string
  sku_id: string | null
  sku_code: string | null
  name: string
  qty: number
  unit_price: string
  mapped: boolean
  bundle: boolean
  components: { sku_id: string; sku_code: string; name: string; qty: number }[]
}

export type OrderDetail = {
  id: string
  external_order_id: string
  order_status: string
  payment_status: string
  fulfillment_status: string
  hold_reason: string
  hold_note: string | null
  payment_method: string
  currency: string
  grand_total: string
  ordered_at: string
  paid_at: string | null
  ship_by: string | null
  version: number
  supports_cancel_request: boolean
  channel_account: { id: string; channel: string; mode: string; status: string }
  lines: OrderLine[]
  reservations: {
    id: string
    sku_id: string
    sku_code: string
    warehouse_id: string
    warehouse_code: string
    qty: number
    status: string
    expires_at: string | null
  }[]
  shipments: {
    id: string
    tracking_no: string | null
    carrier: string | null
    status: string
    shipped_at: string | null
  }[]
  recipient: {
    name_masked: string | null
    phone_masked: string | null
    province: string | null
    postcode: string | null
    pii_status: string
  }
  timeline: TimelineEntry[]
}

export type OrderFilters = {
  fulfillment_status: string
  order_status: string
  payment_status: string
  hold_reason: string
  q: string
  ordered_from: string
  ordered_to: string
}

export type HoldGroup = {
  hold_reason: string
  hold_detail: string | null
  count: number
  samples: { id: string; external_order_id: string; ordered_at: string }[]
}

export const ORDER_PAGE_SIZE = 25

export const ordersApi = {
  list(filters: OrderFilters, limit: number, offset: number): Promise<Page<OrderListItem>> {
    const params = new URLSearchParams()
    params.set('limit', String(limit))
    params.set('offset', String(offset))
    if (filters.fulfillment_status) params.set('fulfillment_status', filters.fulfillment_status)
    if (filters.order_status) params.set('order_status', filters.order_status)
    if (filters.payment_status) params.set('payment_status', filters.payment_status)
    if (filters.hold_reason) params.set('hold_reason', filters.hold_reason)
    if (filters.q) params.set('q', filters.q)
    if (filters.ordered_from) params.set('ordered_from', filters.ordered_from)
    if (filters.ordered_to) params.set('ordered_to', filters.ordered_to)
    return apiRequest(`/api/v1/orders?${params}`)
  },
  detail(id: string): Promise<OrderDetail> {
    return apiRequest(`/api/v1/orders/${id}`)
  },
  holds(): Promise<{ groups: HoldGroup[] }> {
    return apiRequest('/api/v1/orders/holds')
  },
  requestCancel(id: string, reason: string): Promise<{ cancel_request_id: string | null; status: string }> {
    return apiRequest(`/api/v1/orders/${id}/cancel-requests`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    })
  },
}

export function ordersMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError) return err.message || fallback
  return fallback
}
