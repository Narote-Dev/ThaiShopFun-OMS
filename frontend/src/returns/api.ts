import { apiRequest, ApiError } from '../api/client'
import { labelTh } from '../ui/labels'

export type ReturnLine = {
  return_line_id: string
  order_line_id: string
  external_line_id: string
  name: string
  qty: number
  condition: string | null
  restocked_qty: number
  damaged_qty: number
  sku_id: string | null
}

export type ReturnDetail = {
  id: string
  order_id: string
  external_order_id: string
  external_return_id: string
  type: string
  status: string
  reason: string | null
  requested_at: string
  received_at: string | null
  lines: ReturnLine[]
  can_receive: boolean
}

export const returnsApi = {
  detail(id: string): Promise<ReturnDetail> {
    return apiRequest(`/api/v1/returns/${id}`)
  },
  receive(
    id: string,
    lines: { return_line_id: string; condition: string; qty: number }[],
  ): Promise<{ id: string; status: string; received_at: string | null }> {
    return apiRequest(`/api/v1/returns/${id}/receive`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
      },
      body: JSON.stringify({ lines }),
    })
  },
}

export function returnsMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    const known: Record<string, string> = {
      RETURN_NOT_RECEIVABLE: labelTh('RETURN_NOT_RECEIVABLE'),
      RETURN_QTY_EXCEEDED: labelTh('RETURN_QTY_EXCEEDED'),
      RETURN_RECEIVE_QTY_EXCEEDED: labelTh('RETURN_RECEIVE_QTY_EXCEEDED'),
      SKU_NOT_MAPPED: labelTh('SKU_NOT_MAPPED'),
      VALIDATION_FAILED: labelTh('VALIDATION_FAILED'),
    }
    if (known[err.code]) return known[err.code]
    const fromCode = labelTh(err.code)
    return fromCode === 'ไม่ระบุ' ? fallback : fromCode
  }
  return fallback
}
