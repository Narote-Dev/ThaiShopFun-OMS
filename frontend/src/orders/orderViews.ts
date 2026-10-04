import type { OrderFilters } from './api'
import { bangkokTodayIso } from '../ui/format'

export type OrdersTabId = 'all' | 'ready' | 'hold' | 'cancelled'

export const ORDER_TAB_LABELS: Record<OrdersTabId, string> = {
  all: 'ทั้งหมด',
  ready: 'รอหยิบ',
  hold: 'ค้าง (Hold)',
  cancelled: 'ยกเลิก',
}

export const FULFILLMENT_FILTER_VALUES = [
  'UNFULFILLED',
  'READY_TO_PICK',
  'PICKING',
  'PACKED',
  'SHIPPED',
  'DELIVERED',
] as const

export type DateScope = { ordered_from: string; ordered_to: string }

export function emptyOrderFilters(today = bangkokTodayIso()): OrderFilters {
  return {
    fulfillment_status: '',
    order_status: '',
    payment_status: '',
    hold_reason: '',
    channel: '',
    q: '',
    ordered_from: today,
    ordered_to: today,
  }
}

export function noDateOrderFilters(): OrderFilters {
  return {
    fulfillment_status: '',
    order_status: '',
    payment_status: '',
    hold_reason: '',
    channel: '',
    q: '',
    ordered_from: '',
    ordered_to: '',
  }
}

export function filtersForTab(tab: OrdersTabId, dates: DateScope): OrderFilters {
  const base: OrderFilters = {
    fulfillment_status: '',
    order_status: '',
    payment_status: '',
    hold_reason: '',
    channel: '',
    q: '',
    ordered_from: dates.ordered_from,
    ordered_to: dates.ordered_to,
  }
  switch (tab) {
    case 'all':
      return base
    case 'ready':
      return { ...base, fulfillment_status: 'READY_TO_PICK', order_status: 'ACTIVE' }
    case 'hold':
      return { ...base, hold_reason: 'ANY' }
    case 'cancelled':
      return { ...base, order_status: 'CANCELLED' }
    default:
      return base
  }
}

/** Maps applied filters to a tab when they match a tab preset exactly (plus date/q/channel extras). */
export function tabFromFilters(filters: OrderFilters): OrdersTabId {
  if (filters.order_status === 'CANCELLED') {
    return 'cancelled'
  }
  if (filters.hold_reason === 'ANY' && !filters.fulfillment_status && filters.order_status !== 'CANCELLED') {
    return 'hold'
  }
  const noHoldFilter = !filters.hold_reason || filters.hold_reason === 'NONE'
  if (
    filters.fulfillment_status === 'READY_TO_PICK' &&
    (filters.order_status === 'ACTIVE' || filters.order_status === '') &&
    noHoldFilter
  ) {
    return 'ready'
  }
  return 'all'
}
