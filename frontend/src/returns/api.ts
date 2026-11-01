import { apiRequest, ApiError } from '../api/client'
import { labelTh, messageTh } from '../ui/labels'

export type ReturnLine = {
  return_line_id: string
  order_line_id: string
  external_line_id: string
  name: string
  qty: number
  condition: string | null
  restocked_qty: number
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
    lines: { return_line_id: string; condition: string }[],
  ): Promise<{ id: string; status: string; received_at: string }> {
    return apiRequest(`/api/v1/returns/${id}/receive`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ lines }),
    })
  },
}

export function returnsMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    const known: Record<string, string> = {
      RETURN_NOT_RECEIVABLE: 'คำขอคืนนี้ยังไม่พร้อมรับของ',
      RETURN_QTY_EXCEEDED: 'จำนวนคืนเกินที่ส่งแล้ว',
      SKU_NOT_MAPPED: 'บรรทัดคืนยังไม่ได้จับคู่ SKU',
    }
    if (known[err.code]) return known[err.code]
    return messageTh(err.message, labelTh(err.code) === 'ไม่ระบุ' ? fallback : labelTh(err.code))
  }
  return fallback
}
