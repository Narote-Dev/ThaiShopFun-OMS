import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { configureApi, resetApiForTests } from '../api/client'
import { stubFetch } from '../catalog/testFetch'
import OrderDetailPage from './OrderDetailPage'
import type { Me } from '../auth/AuthContext'

const me: Me = {
  tenant: { id: 't1', name: 'Shop', membership_tier: 'PRO', tsf_shop_id: 'shop' },
  role: 'OWNER', entitlement: { status: 'ACTIVE', expires_at: null, ent_ver: 1 },
}
afterEach(() => { cleanup(); resetApiForTests() })
it('shows a payment snapshot and succeeded/failed refunds with Thai labels', async () => {
  const { fetchImpl } = stubFetch(() => ({ body: {
    id: 'o1', external_order_id: 'TSF-1', order_status: 'ACTIVE', payment_status: 'PARTIALLY_REFUNDED',
    fulfillment_status: 'UNFULFILLED', hold_reason: 'NONE', hold_detail: null, hold_note: null,
    payment_method: 'PREPAID', currency: 'THB', grand_total: '580',
    ordered_at: '2026-09-29T08:15:02Z', paid_at: null, ship_by: null, version: 1,
    supports_cancel_request: false,
    channel_account: { id: 'ca', channel: 'TSF', mode: 'ACTIVE', status: 'CONNECTED' },
    lines: [], reservations: [], shipments: [], timeline: [],
    recipient: { name_masked: null, phone_masked: null, province: null, postcode: null, pii_status: 'REDACTED' },
    payment_snapshot: { status: 'PAID', amount: '580', refunded_amount: '0', currency: 'THB', paid_at: null, observed_at: '2026-09-29T08:15:02Z' },
    refunds: [
      { id: 'r1', status: 'SUCCEEDED', amount: '295', currency: 'THB', observed_at: '2026-09-29T08:16:02Z' },
      { id: 'r2', status: 'FAILED', amount: '285', currency: 'THB', observed_at: '2026-09-29T08:17:02Z' },
    ],
  } }))
  configureApi({ getAccessToken: () => 'token', fetchImpl })
  render(<OrderDetailPage id="o1" me={me} />)
  const table = await screen.findByRole('table', { name: 'รายการคืนเงิน' })
  expect(within(table).getByText('สำเร็จ')).toBeInTheDocument()
  expect(within(table).getByText('ไม่สำเร็จ')).toBeInTheDocument()
  expect(within(table).getByText(/295/)).toBeInTheDocument()
  expect(screen.getByText('การชำระเงินและคืนเงิน')).toBeInTheDocument()
  expect(table.textContent).not.toMatch(/SUCCEEDED|FAILED/)
})
