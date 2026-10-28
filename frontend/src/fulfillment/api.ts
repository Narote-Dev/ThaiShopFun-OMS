import { apiRequest, ApiError } from '../api/client'
import { labelTh, messageTh } from '../ui/labels'

export type PickCandidate = {
  id: string
  external_order_id: string
  ordered_at: string
  ship_by: string | null
  line_count: number
}

export type PickListDetail = {
  id: string
  warehouse_code: string
  created_at: string
  orders: { id: string; external_order_id: string; sequence: number }[]
  pick_lines: {
    sku_id: string
    sku_code: string
    barcode: string | null
    name: string
    warehouse_code: string
    qty: number
  }[]
}

export type PackLine = {
  line_id: string
  sku_id: string | null
  sku_code: string | null
  barcode: string | null
  name: string
  required_qty: number
  packed_qty: number
  mapped: boolean
}

export type PackSession = {
  id: string
  external_order_id: string
  fulfillment_status: string
  version: number
  recipient: {
    name: string
    phone: string
    address: string
    province: string
    postcode: string
    pii_status: string
  }
  lines: PackLine[]
  complete: boolean
}

export function fulfillmentMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError) return messageTh(err.code, err.message)
  if (err instanceof Error && err.message) return err.message
  return fallback
}

export const pickListsApi = {
  async candidates(): Promise<{ items: PickCandidate[] }> {
    return apiRequest('/api/v1/pick-lists/candidates')
  },
  async create(orderIds: string[]): Promise<{ id: string; order_count: number; pdf_url: string }> {
    return apiRequest('/api/v1/pick-lists', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ order_ids: orderIds }),
    })
  },
  async detail(id: string): Promise<PickListDetail> {
    return apiRequest(`/api/v1/pick-lists/${id}`)
  },
  async openPdf(id: string): Promise<void> {
    const token = await import('../auth/session').then((m) => m.getAccessToken())
    const response = await fetch(`/api/v1/pick-lists/${id}/pdf`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!response.ok) {
      const body = await response.json().catch(() => ({}))
      throw new ApiError(
        response.status,
        body.error ?? 'ERROR',
        body.message ?? labelTh('PICK_PDF_ERROR'),
        body.trace_id ?? null,
      )
    }
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    window.open(url, '_blank', 'noopener,noreferrer')
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
  },
}

export const packApi = {
  async session(orderId: string): Promise<PackSession> {
    return apiRequest(`/api/v1/pack/orders/${orderId}`)
  },
  async scan(orderId: string, scan: string) {
    return apiRequest<{ packed_qty: number; required_qty: number; complete: boolean; sku_code: string }>(
      `/api/v1/pack/orders/${orderId}/scans`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ scan }),
      },
    )
  },
  async complete(orderId: string) {
    return apiRequest<{ fulfillment_status: string; version: number }>(
      `/api/v1/pack/orders/${orderId}/complete`,
      { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' },
    )
  },
}
