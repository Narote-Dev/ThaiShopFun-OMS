import { apiRequest, ApiError } from '../api/client'
import type { Page } from '../catalog/api'

export const DOCUMENT_TYPES = ['OPENING', 'RECEIVE', 'ADJUSTMENT', 'COUNT', 'WRITE_OFF'] as const
export type DocumentType = (typeof DOCUMENT_TYPES)[number]
export type DocumentStatus = 'DRAFT' | 'POSTED' | 'VOID'

export const ADJUSTMENT_REASONS = ['DAMAGED', 'LOST', 'FOUND', 'DATA_ENTRY', 'OTHER'] as const

export const LEDGER_REASONS = [
  'OPENING_BALANCE',
  'RECEIVE',
  'ADJUST_IN',
  'ADJUST_OUT',
  'COUNT_CORRECTION',
  'DAMAGE_WRITE_OFF',
  'RETURN_RESTOCK',
  'SHIP',
  'RESERVE',
  'RELEASE',
  'UNPACK',
] as const

export const TYPE_LABELS: Record<DocumentType, string> = {
  OPENING: 'Opening balance',
  RECEIVE: 'Receive',
  ADJUSTMENT: 'Adjustment',
  COUNT: 'Stock count',
  WRITE_OFF: 'Write-off',
}

export type DocumentLine = {
  id: string
  sku_id: string
  sku_code: string
  sku_name: string
  warehouse_id: string
  warehouse_code: string
  qty: number
  system_qty_at_start: number | null
  counted_qty: number | null
  reason_code: string | null
  on_hand: number | null
  reserved: number | null
}

export type StockDocument = {
  id: string
  type: DocumentType
  status: DocumentStatus
  reference_no: string | null
  note: string | null
  count_started_at: string | null
  posted_at: string | null
  posted_by: string | null
  line_count: number
  warehouse_ids: string[]
  created_at: string
  updated_at: string
  lines?: DocumentLine[]
}

export type LineInput = {
  sku_id?: string
  sku_code?: string
  warehouse_id?: string | null
  qty?: number | null
  counted_qty?: number | null
  reason_code?: string | null
}

export type Movement = {
  ledger_id: string
  line_id: string
  sku_id: string
  warehouse_id: string
  reason: string
  delta_on_hand: number
}

export type DocumentMovement = {
  document_id: string
  type: DocumentType
  status: DocumentStatus
  posted_at: string | null
  posted_by: string | null
  voided_at: string | null
  movements: Movement[]
}

/** One entry of a refused post or void (`errors` in the 4.8 body). */
export type LineProblem = {
  line_id?: string
  sku_id: string
  warehouse_id: string
  error: string
  message: string
  on_hand?: number
  reserved?: number
  delta?: number
}

export type HistoryLink = {
  kind: 'stock_document' | 'reservation' | 'return_line'
  document_id?: string
  document_type?: DocumentType
  document_status?: DocumentStatus
  reference_no?: string
  reservation_group_id?: string
  owner_type?: string
  order_ref?: string
  return_line_id?: string
}

export type HistoryEntry = {
  id: string
  created_at: string
  warehouse_id: string
  warehouse_code: string
  reason: string
  delta_on_hand: number
  delta_reserved: number
  on_hand_after: number
  reserved_after: number
  actor: string | null
  ref_type: string | null
  ref_id: string | null
  link: HistoryLink | null
}

export type HistoryPage = {
  sku: { id: string; sku_code: string; name: string }
  items: HistoryEntry[]
  next_cursor: string | null
}

export type DocumentFilters = { type: string; status: string; from: string; to: string }
export type HistoryFilters = { reason: string; from: string; to: string; warehouse_id: string }

const json = (body: unknown): RequestInit => ({ body: JSON.stringify(body) })

function query(values: Record<string, string | number | null | undefined>): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(values)) {
    if (value !== null && value !== undefined && String(value).trim() !== '') params.set(key, String(value).trim())
  }
  const text = params.toString()
  return text ? `?${text}` : ''
}

const base = '/api/v1/stock-documents'

export const stockApi = {
  listDocuments: (filters: DocumentFilters, limit: number, offset: number) =>
    apiRequest<Page<StockDocument>>(`${base}${query({ ...filters, limit, offset })}`),
  getDocument: (id: string) => apiRequest<StockDocument>(`${base}/${id}`),
  createDocument: (type: DocumentType, referenceNo: string | null, note: string | null) =>
    apiRequest<StockDocument>(base, { method: 'POST', ...json({ type, reference_no: referenceNo, note }) }),
  updateDocument: (id: string, referenceNo: string | null, note: string | null) =>
    apiRequest<StockDocument>(`${base}/${id}`, { method: 'PUT', ...json({ reference_no: referenceNo, note }) }),
  deleteDocument: (id: string) => apiRequest<void>(`${base}/${id}`, { method: 'DELETE' }),
  addLine: (id: string, line: LineInput) =>
    apiRequest<DocumentLine>(`${base}/${id}/lines`, { method: 'POST', ...json(line) }),
  updateLine: (id: string, lineId: string, line: LineInput) =>
    apiRequest<DocumentLine>(`${base}/${id}/lines/${lineId}`, { method: 'PUT', ...json(line) }),
  deleteLine: (id: string, lineId: string) =>
    apiRequest<void>(`${base}/${id}/lines/${lineId}`, { method: 'DELETE' }),
  startCount: (id: string) => apiRequest<StockDocument>(`${base}/${id}/start-count`, { method: 'POST' }),
  post: (id: string) => apiRequest<DocumentMovement>(`${base}/${id}/post`, { method: 'POST' }),
  voidDocument: (id: string) => apiRequest<DocumentMovement>(`${base}/${id}/void`, { method: 'POST' }),
  history: (skuId: string, filters: HistoryFilters, cursor: string | null, limit = 50) =>
    apiRequest<HistoryPage>(`/api/v1/skus/${skuId}/stock-history${query({ ...filters, cursor, limit })}`),
}

/** Per-line problems of a refused post or void, or an empty list. */
export function problemsOf(err: unknown): LineProblem[] {
  if (err instanceof ApiError && Array.isArray(err.details)) return err.details as LineProblem[]
  return []
}

const MESSAGES: Record<string, string> = {
  ENTITLEMENT_GRACE: 'This shop is read-only until membership is renewed.',
  FORBIDDEN: 'Only an OWNER or ADMIN can post this document type or void a document.',
  BELOW_RESERVED: 'Stock would drop below what is reserved for orders. Release or move those orders first.',
  REASON_REQUIRED: 'Every adjustment line needs a reason.',
  OPENING_ALREADY_SET: 'This SKU already has stock history. Use an adjustment instead.',
}

export function stockMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError && MESSAGES[err.code]) return `${MESSAGES[err.code]} (${err.code})`
  if (err instanceof ApiError) return `${err.message || fallback} (${err.code})`
  if (err instanceof Error && err.message) return err.message
  return fallback
}
