import type { Me } from '../auth/AuthContext'
import type { OrderDetail } from './api'

export type OrdersAccess = {
  grace: boolean
  canRequestCancel: (order: OrderDetail) => boolean
}

export function ordersAccess(me: Me): OrdersAccess {
  const grace = me.entitlement.status === 'GRACE'
  const manager = me.role === 'OWNER' || me.role === 'ADMIN'
  return {
    grace,
    canRequestCancel: (order) =>
      manager &&
      !grace &&
      order.supports_cancel_request &&
      order.order_status === 'ACTIVE' &&
      order.fulfillment_status !== 'SHIPPED' &&
      order.fulfillment_status !== 'DELIVERED',
  }
}
