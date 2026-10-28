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
  if (err instanceof ApiError) return messageTh(err.code, fallback)
  if (err instanceof Error && err.message && /[ก-๙]/.test(err.message)) return err.message
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

export type ShipCandidate = {
  id: string
  external_order_id: string
  ordered_at: string
  ship_by: string | null
  has_shipment: boolean
  tracking_no: string | null
  carrier: string | null
  shipment_status: string | null
}

export type LabelBatchOrderResult = {
  order_id: string
  external_order_id: string
  ok: boolean
  error: string | null
  message: string | null
}

export type LabelBatchResponse = {
  pdf_base64: string | null
  content_type: string | null
  results: LabelBatchOrderResult[]
}

function openPdfBase64(pdfBase64: string, contentType: string | null) {
  const binary = atob(pdfBase64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  const blob = new Blob([bytes], { type: contentType ?? 'application/pdf' })
  const url = URL.createObjectURL(blob)
  window.open(url, '_blank', 'noopener,noreferrer')
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

export const shipApi = {
  async candidates(): Promise<{ items: ShipCandidate[] }> {
    return apiRequest('/api/v1/ship/candidates')
  },
  async labelBatch(orderIds: string[]): Promise<LabelBatchResponse> {
    return apiRequest('/api/v1/ship/labels', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ order_ids: orderIds }),
    })
  },
  async openLabelBatch(orderIds: string[]): Promise<LabelBatchResponse> {
    const data = await shipApi.labelBatch(orderIds)
    if (data.pdf_base64) {
      openPdfBase64(data.pdf_base64, data.content_type)
    }
    return data
  },
  async manualTracking(orderId: string, body: { carrier: string; tracking_no: string }) {
    return apiRequest(`/api/v1/ship/orders/${orderId}/manual-tracking`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
  },
  async confirmShip(orderId: string) {
    return apiRequest(`/api/v1/ship/orders/${orderId}/confirm`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    })
  },
}
