export type StatusKind =
  | 'fulfillment'
  | 'order'
  | 'payment'
  | 'hold'
  | 'payment_method'
  | 'channel'
  | 'generic'

export type StatusVariant = 'success' | 'danger' | 'warning' | 'muted' | 'info' | 'channel' | 'violet' | 'neutral'

export type StatusStyle = {
  variant: StatusVariant
  label?: string
  strikethrough?: boolean
}

const FULFILLMENT: Record<string, StatusStyle> = {
  READY_TO_PICK: { variant: 'success', label: 'READY_TO_PICK' },
  PICKING: { variant: 'info', label: 'PICKING' },
  PACKED: { variant: 'info', label: 'PACKED' },
  SHIPPED: { variant: 'neutral', label: 'SHIPPED' },
  DELIVERED: { variant: 'success', label: 'DELIVERED' },
  UNFULFILLED: { variant: 'muted', label: 'UNFULFILLED' },
}

const ORDER: Record<string, StatusStyle> = {
  ACTIVE: { variant: 'success', label: 'ACTIVE' },
  CANCELLED: { variant: 'muted', label: 'CANCELLED', strikethrough: true },
  COMPLETED: { variant: 'neutral', label: 'COMPLETED' },
}

const PAYMENT: Record<string, StatusStyle> = {
  PENDING: { variant: 'warning', label: 'PENDING' },
  PAID: { variant: 'success', label: 'PAID' },
  COD_PENDING: { variant: 'violet', label: 'COD_PENDING' },
  PARTIALLY_REFUNDED: { variant: 'warning', label: 'PARTIALLY_REFUNDED' },
  REFUNDED: { variant: 'muted', label: 'REFUNDED' },
}

const HOLD: Record<string, StatusStyle> = {
  NONE: { variant: 'muted', label: '—' },
  SKU_NOT_MAPPED: { variant: 'warning', label: 'SKU_NOT_MAPPED' },
  OUT_OF_STOCK: { variant: 'danger', label: 'OUT_OF_STOCK' },
  ADDRESS_PROBLEM: { variant: 'danger', label: 'ADDRESS_PROBLEM' },
  PAYMENT_MISMATCH: { variant: 'danger', label: 'PAYMENT_MISMATCH' },
  CHANNEL_CANCEL_PENDING: { variant: 'danger', label: 'CHANNEL_CANCEL_PENDING' },
  MANUAL: { variant: 'warning', label: 'MANUAL' },
  ANY: { variant: 'danger', label: 'ON HOLD' },
}

const PAYMENT_METHOD: Record<string, StatusStyle> = {
  COD: { variant: 'violet', label: 'COD' },
  PREPAID: { variant: 'info', label: 'PREPAID' },
}

const CHANNEL: Record<string, StatusStyle> = {
  TSF: { variant: 'channel', label: 'TSF' },
  SHOPEE: { variant: 'neutral', label: 'SHOPEE' },
  LAZADA: { variant: 'neutral', label: 'LAZADA' },
  TIKTOK: { variant: 'neutral', label: 'TIKTOK' },
}

export function resolveStatus(value: string, kind: StatusKind = 'generic'): StatusStyle {
  const key = value.trim()
  if (!key) return { variant: 'muted', label: '—' }
  const maps: Record<StatusKind, Record<string, StatusStyle> | undefined> = {
    fulfillment: FULFILLMENT,
    order: ORDER,
    payment: PAYMENT,
    hold: HOLD,
    payment_method: PAYMENT_METHOD,
    channel: CHANNEL,
    generic: undefined,
  }
  const map = maps[kind]
  if (map && map[key]) return map[key]
  if (kind === 'hold' && key !== 'NONE') return { variant: 'danger', label: key }
  return { variant: 'neutral', label: key }
}

/** Every enum the UI may render — used by tests to ensure coverage. */
export const ALL_STATUS_VALUES: { kind: StatusKind; value: string }[] = [
  ...Object.keys(FULFILLMENT).map((value) => ({ kind: 'fulfillment' as const, value })),
  ...Object.keys(ORDER).map((value) => ({ kind: 'order' as const, value })),
  ...Object.keys(PAYMENT).map((value) => ({ kind: 'payment' as const, value })),
  ...Object.keys(HOLD).map((value) => ({ kind: 'hold' as const, value })),
  ...Object.keys(PAYMENT_METHOD).map((value) => ({ kind: 'payment_method' as const, value })),
  ...Object.keys(CHANNEL).map((value) => ({ kind: 'channel' as const, value })),
]
