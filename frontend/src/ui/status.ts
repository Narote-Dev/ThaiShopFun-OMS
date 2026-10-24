import { labelTh } from './labels'

export type StatusKind =
  | 'fulfillment'
  | 'order'
  | 'payment'
  | 'hold'
  | 'hold_reason'
  | 'payment_method'
  | 'channel'
  | 'stock_document'
  | 'product'
  | 'outbox'
  | 'generic'

export type StatusVariant =
  | 'success'
  | 'danger'
  | 'warning'
  | 'muted'
  | 'info'
  | 'channel'
  | 'violet'
  | 'neutral'

export type StatusStyle = {
  variant: StatusVariant
  label?: string
  strikethrough?: boolean
}

const FULFILLMENT: Record<string, StatusStyle> = {
  READY_TO_PICK: { variant: 'success', label: labelTh('READY_TO_PICK') },
  PICKING: { variant: 'info', label: labelTh('PICKING') },
  PACKED: { variant: 'info', label: labelTh('PACKED') },
  SHIPPED: { variant: 'neutral', label: labelTh('SHIPPED') },
  DELIVERED: { variant: 'success', label: labelTh('DELIVERED') },
  UNFULFILLED: { variant: 'muted', label: labelTh('UNFULFILLED') },
}

const ORDER: Record<string, StatusStyle> = {
  ACTIVE: { variant: 'success', label: labelTh('ACTIVE') },
  CANCELLED: { variant: 'muted', label: labelTh('CANCELLED'), strikethrough: true },
  COMPLETED: { variant: 'neutral', label: labelTh('COMPLETED') },
}

const PAYMENT: Record<string, StatusStyle> = {
  PENDING: { variant: 'warning', label: labelTh('PENDING') },
  PAID: { variant: 'success', label: labelTh('PAID') },
  COD_PENDING: { variant: 'violet', label: labelTh('COD_PENDING') },
  PARTIALLY_REFUNDED: { variant: 'warning', label: labelTh('PARTIALLY_REFUNDED') },
  REFUNDED: { variant: 'muted', label: labelTh('REFUNDED') },
}

const HOLD_REASON: Record<string, StatusStyle> = {
  NONE: { variant: 'muted', label: '—' },
  SKU_NOT_MAPPED: { variant: 'warning', label: labelTh('SKU_NOT_MAPPED') },
  OUT_OF_STOCK: { variant: 'danger', label: labelTh('OUT_OF_STOCK') },
  ADDRESS_PROBLEM: { variant: 'danger', label: labelTh('ADDRESS_PROBLEM') },
  PAYMENT_MISMATCH: { variant: 'danger', label: labelTh('PAYMENT_MISMATCH') },
  CHANNEL_CANCEL_PENDING: { variant: 'danger', label: labelTh('CHANNEL_CANCEL_PENDING') },
  MANUAL: { variant: 'warning', label: labelTh('MANUAL') },
}


const PAYMENT_METHOD: Record<string, StatusStyle> = {
  COD: { variant: 'violet', label: labelTh('COD') },
  PREPAID: { variant: 'info', label: labelTh('PREPAID') },
}

const CHANNEL: Record<string, StatusStyle> = {
  TSF: { variant: 'channel', label: labelTh('TSF') },
  SHOPEE: { variant: 'neutral', label: labelTh('SHOPEE') },
  LAZADA: { variant: 'neutral', label: labelTh('LAZADA') },
  TIKTOK: { variant: 'neutral', label: labelTh('TIKTOK') },
}

const STOCK_DOCUMENT: Record<string, StatusStyle> = {
  DRAFT: { variant: 'warning', label: labelTh('DRAFT') },
  POSTED: { variant: 'success', label: labelTh('POSTED') },
  VOID: { variant: 'muted', label: labelTh('VOID') },
}

const PRODUCT: Record<string, StatusStyle> = {
  ACTIVE: { variant: 'success', label: labelTh('ACTIVE') },
  INACTIVE: { variant: 'muted', label: labelTh('INACTIVE') },
}

const OUTBOX: Record<string, StatusStyle> = {
  DEAD: { variant: 'danger', label: labelTh('DEAD') },
  PENDING: { variant: 'warning', label: labelTh('PENDING') },
  SENT: { variant: 'success', label: labelTh('SENT') },
}

export function resolveStatus(value: string, kind: StatusKind = 'generic'): StatusStyle {
  const key = value.trim()
  if (!key) return { variant: 'muted', label: '—' }
  const maps: Record<StatusKind, Record<string, StatusStyle> | undefined> = {
    fulfillment: FULFILLMENT,
    order: ORDER,
    payment: PAYMENT,
    hold: HOLD_REASON,
    hold_reason: HOLD_REASON,
    payment_method: PAYMENT_METHOD,
    channel: CHANNEL,
    stock_document: STOCK_DOCUMENT,
    product: PRODUCT,
    outbox: OUTBOX,
    generic: undefined,
  }
  const map = maps[kind]
  if (map && map[key]) return map[key]
  if ((kind === 'hold' || kind === 'hold_reason') && key !== 'NONE') {
    return { variant: 'danger', label: labelTh(key) }
  }
  return { variant: 'neutral', label: labelTh(key) }
}

export function holdReasonLabelTh(reason: string): string {
  if (reason === 'NONE' || !reason) return '—'
  return labelTh(reason)
}

export type OrderDisplayInput = {
  order_status: string
  fulfillment_status: string
  hold_reason: string
}

/** Combined list/detail status: CANCELLED > ON HOLD > fulfillment. */
export function resolveOrderDisplayStatus(order: OrderDisplayInput): StatusStyle {
  if (order.order_status === 'CANCELLED') {
    return { variant: 'muted', label: labelTh('CANCELLED'), strikethrough: true }
  }
  if (order.hold_reason && order.hold_reason !== 'NONE') {
    return { variant: 'danger', label: labelTh('ON HOLD') }
  }
  return resolveStatus(order.fulfillment_status, 'fulfillment')
}

export const ALL_STATUS_VALUES: { kind: StatusKind; value: string }[] = [
  ...Object.keys(FULFILLMENT).map((value) => ({ kind: 'fulfillment' as const, value })),
  ...Object.keys(ORDER).map((value) => ({ kind: 'order' as const, value })),
  ...Object.keys(PAYMENT).map((value) => ({ kind: 'payment' as const, value })),
  ...Object.keys(HOLD_REASON).map((value) => ({ kind: 'hold_reason' as const, value })),
  ...Object.keys(PAYMENT_METHOD).map((value) => ({ kind: 'payment_method' as const, value })),
  ...Object.keys(CHANNEL).map((value) => ({ kind: 'channel' as const, value })),
  ...Object.keys(STOCK_DOCUMENT).map((value) => ({ kind: 'stock_document' as const, value })),
  ...Object.keys(PRODUCT).map((value) => ({ kind: 'product' as const, value })),
  ...Object.keys(OUTBOX).map((value) => ({ kind: 'outbox' as const, value })),
]
