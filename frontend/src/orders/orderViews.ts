import type { OrderFilters } from './api'
import { bangkokTodayIso } from '../ui/format'

export type OrdersTabId = 'all' | 'ready' | 'hold' | 'unshipped' | 'shipped' | 'cancelled'

export const ORDER_TAB_LABELS: Record<OrdersTabId, string> = {
  all: 'ทั้งหมด',
  ready: 'รอหยิบ',
  hold: 'ค้าง (Hold)',
  unshipped: 'ยังไม่จัดส่ง',
  shipped: 'จัดส่งแล้ว',
  cancelled: 'ยกเลิก',
}

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

export function filtersForTab(tab: OrdersTabId, today = bangkokTodayIso()): OrderFilters {
  const base = emptyOrderFilters(today)
  switch (tab) {
    case 'all':
      return base
    case 'ready':
      return { ...base, fulfillment_status: 'READY_TO_PICK', order_status: 'ACTIVE' }
    case 'hold':
      return { ...base, hold_reason: 'ANY' }
    case 'cancelled':
      return { ...base, order_status: 'CANCELLED' }
    case 'unshipped':
      return { ...base, fulfillment_status: 'UNFULFILLED' }
    case 'shipped':
      return { ...base, fulfillment_status: 'SHIPPED' }
    default:
      return base
  }
}

export function tabFromFilters(filters: OrderFilters): OrdersTabId {
  if (filters.order_status === 'CANCELLED') return 'cancelled'
  if (filters.hold_reason === 'ANY') return 'hold'
  if (filters.fulfillment_status === 'READY_TO_PICK') return 'ready'
  if (filters.fulfillment_status === 'SHIPPED' || filters.fulfillment_status === 'DELIVERED') return 'shipped'
  if (filters.fulfillment_status === 'UNFULFILLED') return 'unshipped'
  return 'all'
}
