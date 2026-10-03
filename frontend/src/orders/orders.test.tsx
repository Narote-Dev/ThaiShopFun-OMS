import { describe, expect, it } from 'vitest'
import { ordersAccess } from './access'
import type { OrderDetail } from './api'
import type { Me } from '../auth/AuthContext'

const baseMe = (role: string, status: string): Me => ({
  tenant: { id: 't1', name: 'Shop', membership_tier: 'PRO', tsf_shop_id: 'shop' },
  role,
  entitlement: { status, expires_at: null, ent_ver: 1 },
})

const order: OrderDetail = {
  id: '1',
  external_order_id: 'X',
  order_status: 'ACTIVE',
  payment_status: 'PAID',
  fulfillment_status: 'READY_TO_PICK',
  hold_reason: 'NONE',
  hold_detail: null,
  hold_note: null,
  payment_method: 'PREPAID',
  currency: 'THB',
  grand_total: '1',
  ordered_at: '2026-01-01T00:00:00Z',
  paid_at: null,
  ship_by: null,
  version: 1,
  supports_cancel_request: true,
  channel_account: { id: 'c', channel: 'TSF', mode: 'ACTIVE', status: 'CONNECTED' },
  lines: [],
  reservations: [],
  shipments: [],
  recipient: { name_masked: 'ส***', phone_masked: '***-***-1234', province: 'B', postcode: '1', pii_status: 'ACTIVE' },
  timeline: [],
}

describe('ordersAccess', () => {
  it('allows cancel for owner on active ready order', () => {
    const access = ordersAccess(baseMe('OWNER', 'ACTIVE'))
    expect(access.canRequestCancel(order)).toBe(true)
  })

  it('blocks staff and grace', () => {
    expect(ordersAccess(baseMe('STAFF', 'ACTIVE')).canRequestCancel(order)).toBe(false)
    expect(ordersAccess(baseMe('OWNER', 'GRACE')).canRequestCancel(order)).toBe(false)
  })

  it('blocks shipped orders', () => {
    const access = ordersAccess(baseMe('OWNER', 'ACTIVE'))
    expect(access.canRequestCancel({ ...order, fulfillment_status: 'SHIPPED' })).toBe(false)
  })

  it('blocks when channel does not support cancel', () => {
    const access = ordersAccess(baseMe('OWNER', 'ACTIVE'))
    expect(access.canRequestCancel({ ...order, supports_cancel_request: false })).toBe(false)
  })
})
