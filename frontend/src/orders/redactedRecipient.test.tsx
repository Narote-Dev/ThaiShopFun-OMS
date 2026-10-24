import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { configureApi, resetApiForTests } from '../api/client'
import { stubFetch } from '../catalog/testFetch'
import OrderDetailPage from './OrderDetailPage'

afterEach(() => { cleanup(); resetApiForTests() })
it('shows the PDPA banner and province/postcode without masked or empty fields', async () => {
  const { fetchImpl } = stubFetch(() => ({ body: {
    id: 'o1', external_order_id: 'DEMO-CANCELLED', order_status: 'CANCELLED',
    payment_status: 'PENDING', fulfillment_status: 'UNFULFILLED', hold_reason: 'NONE',
    payment_method: 'COD', currency: 'THB', grand_total: '100',
    ordered_at: '2026-01-01T00:00:00Z', version: 1,
    channel_account: { id: 'ca', channel: 'TSF', mode: 'ACTIVE', status: 'CONNECTED' },
    lines: [], timeline: [], reservations: [], shipments: [],
    recipient: { pii_status: 'REDACTED', name_masked: 'ส***', phone_masked: '***-***-1234', province: 'กรุงเทพมหานคร', postcode: '10110' },
  } }))
  configureApi({ getAccessToken: () => 't', fetchImpl })
  render(<OrderDetailPage id="o1" me={{ tenant: { id: 't', name: 'ร้าน', membership_tier: 'PRO', tsf_shop_id: 's' }, role: 'STAFF', entitlement: { status: 'ACTIVE', expires_at: null, ent_ver: 1 } }} />)
  expect(await screen.findByText('ข้อมูลผู้รับถูกลบตามนโยบายคุ้มครองข้อมูลส่วนบุคคล แล้ว (เหลือจังหวัด/รหัสไปรษณีย์)')).toBeInTheDocument()
  expect(screen.getByText('กรุงเทพมหานคร 10110')).toBeInTheDocument()
  expect(screen.queryByText('ส***')).not.toBeInTheDocument()
  expect(screen.queryByText('***-***-1234')).not.toBeInTheDocument()
  const shipping = screen.getByText('ข้อมูลการจัดส่ง').parentElement?.parentElement
  expect(shipping?.textContent).not.toContain('—')
})
