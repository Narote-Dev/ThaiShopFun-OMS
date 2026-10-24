import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { configureApi, resetApiForTests } from '../api/client'
import type { Me } from '../auth/AuthContext'
import { stubFetch } from '../catalog/testFetch'
import { formatBangkokDateTime } from '../ui/format'
import OrderDetailPage from './OrderDetailPage'
import OrdersListPage from './OrdersListPage'
import HoldQueuePage from './HoldQueuePage'
import type { OrderDetail } from './api'

const me: Me = {
  tenant: { id: 't', name: 'Shop', membership_tier: 'PRO', tsf_shop_id: 'shop' },
  role: 'OWNER', entitlement: { status: 'ACTIVE', expires_at: null, ent_ver: 1 },
}
const order: OrderDetail = {
  id: 'o1', external_order_id: 'ORDER-1', order_status: 'ACTIVE', payment_status: 'PAID',
  fulfillment_status: 'UNFULFILLED', hold_reason: 'OUT_OF_STOCK', hold_detail: null, hold_note: null,
  payment_method: 'PREPAID', currency: 'THB', grand_total: '100', ordered_at: '2026-10-24T01:00:00Z',
  paid_at: null, ship_by: null, version: 1, supports_cancel_request: true,
  channel_account: { id: 'ca', channel: 'TSF', mode: 'ACTIVE', status: 'CONNECTED' },
  lines: [], reservations: [], shipments: [], timeline: [],
  recipient: { name_masked: null, phone_masked: null, province: null, postcode: null, pii_status: 'ACTIVE' },
}

afterEach(() => { cleanup(); resetApiForTests(); window.location.hash = '' })

function setup(detail = order, user = me) {
  let current = detail
  const { fetchImpl, calls } = stubFetch(({ method }) => {
    if (method === 'POST') {
      current = { ...detail, hold_reason: 'CHANNEL_CANCEL_PENDING', timeline: [{ dimension: 'HOLD', from_value: detail.hold_reason, to_value: 'CHANNEL_CANCEL_PENDING', at: '2026-10-24T02:00:00Z', actor: 'USER', reason: 'cancel requested' }] }
      return { status: 202, body: { status: 'PENDING', cancel_request_id: 'c1' } }
    }
    return { body: current }
  })
  configureApi({ getAccessToken: () => 't', fetchImpl })
  render(<OrderDetailPage id="o1" me={user} />)
  return calls
}

async function openCancel() {
  fireEvent.click(await screen.findByRole('button', { name: 'ขอยกเลิก' }))
  return within(screen.getByRole('dialog', { name: 'ขอยกเลิกออเดอร์' }))
}

it('validates missing, blank and overlong other reasons inline, accepts 500 characters, then shows pending state', async () => {
  const calls = setup()
  const dialog = await openCancel()
  expect(dialog.getByText('OMS จะส่งคำขอไป TSF — TSF เป็นผู้ตัดสิน')).toBeInTheDocument()
  fireEvent.click(dialog.getByRole('button', { name: 'ส่งคำขอยกเลิก' }))
  expect(dialog.getByText('กรุณาระบุเหตุผล')).toBeInTheDocument()
  fireEvent.change(dialog.getByLabelText('เหตุผล', { exact: true }), { target: { value: 'อื่นๆ' } })
  fireEvent.change(dialog.getByLabelText('เหตุผลอื่นๆ'), { target: { value: '   ' } })
  fireEvent.click(dialog.getByRole('button', { name: 'ส่งคำขอยกเลิก' }))
  expect(dialog.getByText('กรุณาระบุเหตุผล')).toBeInTheDocument()
  fireEvent.change(dialog.getByLabelText('เหตุผลอื่นๆ'), { target: { value: 'ก'.repeat(501) } })
  fireEvent.click(dialog.getByRole('button', { name: 'ส่งคำขอยกเลิก' }))
  expect(dialog.getByText('เหตุผลต้องไม่เกิน 500 ตัวอักษร')).toBeInTheDocument()
  expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0)
  fireEvent.change(dialog.getByLabelText('เหตุผลอื่นๆ'), { target: { value: 'ก'.repeat(500) } })
  fireEvent.click(dialog.getByRole('button', { name: 'ส่งคำขอยกเลิก' }))
  expect(await screen.findByText('รอ TSF ตัดสินคำขอยกเลิก')).toBeInTheDocument()
  expect(screen.getByText(`ส่งคำขอเมื่อ ${formatBangkokDateTime('2026-10-24T02:00:00Z')}`)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'ส่งคำขอแล้ว' })).toBeDisabled()
  expect(screen.queryByRole('dialog')).toBeNull()
  const posts = calls.filter((c) => c.method === 'POST')
  expect(posts).toHaveLength(1)
  expect(posts[0].url).toBe('/api/v1/orders/o1/cancel-requests')
  expect(JSON.parse(String(posts[0].init?.body))).toEqual({ reason: 'ก'.repeat(500) })
})

it.each(['ลูกค้าขอยกเลิก', 'สินค้าหมด', 'ที่อยู่ไม่ถูกต้อง'])('submits the %s preset directly', async (reason) => {
  const calls = setup()
  const dialog = await openCancel()
  fireEvent.change(dialog.getByLabelText('เหตุผล', { exact: true }), { target: { value: reason } })
  expect(dialog.queryByLabelText('เหตุผลอื่นๆ')).toBeNull()
  fireEvent.click(dialog.getByRole('button', { name: 'ส่งคำขอยกเลิก' }))
  await screen.findByText('รอ TSF ตัดสินคำขอยกเลิก')
  expect(JSON.parse(String(calls.find((c) => c.method === 'POST')?.init?.body))).toEqual({ reason })
})

it.each([
  ['STAFF', 'ACTIVE', {}], ['OWNER', 'GRACE', {}],
  ['OWNER', 'ACTIVE', { order_status: 'CANCELLED' }], ['OWNER', 'ACTIVE', { order_status: 'COMPLETED' }],
  ['OWNER', 'ACTIVE', { fulfillment_status: 'SHIPPED' }], ['OWNER', 'ACTIVE', { fulfillment_status: 'DELIVERED' }],
  ['OWNER', 'ACTIVE', { supports_cancel_request: false }],
] as const)('hides cancel for %s / %s / %j', async (role, status, overrides) => {
  setup({ ...order, ...overrides }, { ...me, role, entitlement: { ...me.entitlement, status } })
  await screen.findByRole('heading', { name: 'Order ORDER-1' })
  expect(screen.queryByRole('button', { name: 'ขอยกเลิก' })).toBeNull()
  expect(screen.queryByRole('dialog')).toBeNull()
})

it('allows ADMIN to open the cancel dialog', async () => {
  setup(order, { ...me, role: 'ADMIN' })
  await openCancel()
  expect(screen.getByRole('dialog', { name: 'ขอยกเลิกออเดอร์' })).toBeInTheDocument()
})

it.each(['cancel', 'recheck'])('cancels %s confirmation without an API mutation', async (action) => {
  const calls = setup()
  if (action === 'cancel') await openCancel()
  else fireEvent.click(await screen.findByRole('button', { name: 'Re-check hold' }))
  const dialog = screen.getByRole('dialog')
  expect(dialog).toBeInTheDocument()
  expect(calls.filter((c) => c.method !== 'GET')).toHaveLength(0)
  fireEvent.click(within(dialog).getByRole('button', { name: 'ยกเลิก' }))
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(calls.filter((c) => c.method !== 'GET')).toHaveLength(0)
})

it('shows the pending banner on load and uses the latest matching timeline entry', async () => {
  setup({ ...order, hold_reason: 'CHANNEL_CANCEL_PENDING', timeline: [
    { dimension: 'HOLD', from_value: 'NONE', to_value: 'CHANNEL_CANCEL_PENDING', at: '2026-10-24T03:00:00Z', actor: 'USER', reason: null },
    { dimension: 'HOLD', from_value: 'NONE', to_value: 'CHANNEL_CANCEL_PENDING', at: '2026-10-24T02:00:00Z', actor: 'USER', reason: null },
    { dimension: 'fulfillment_status', from_value: 'UNFULFILLED', to_value: 'READY_TO_PICK', at: '2026-10-24T04:00:00Z', actor: 'USER', reason: null },
  ] })
  expect(await screen.findByText('รอ TSF ตัดสินคำขอยกเลิก')).toBeInTheDocument()
  expect(screen.getByText(`ส่งคำขอเมื่อ ${formatBangkokDateTime('2026-10-24T03:00:00Z')}`)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'ส่งคำขอแล้ว' })).toBeDisabled()
})

it.each([
  [422, 'CAPABILITY_UNSUPPORTED', 'ช่องทางนี้ไม่รองรับคำขอยกเลิก'],
  [409, 'ORDER_NOT_CANCELLABLE', 'ออเดอร์นี้ไม่สามารถขอยกเลิกได้'],
  [409, 'CANCEL_REQUEST_CONFLICT', 'คำขอยกเลิกขัดแย้งกับสถานะออเดอร์ กรุณาโหลดใหม่'],
  [429, 'RATE_LIMITED', 'ส่งคำขอถี่เกินไป กรุณาลองอีกครั้งภายหลัง'],
  [502, 'CHANNEL_ERROR', 'ช่องทางไม่พร้อมให้บริการ กรุณาลองอีกครั้งภายหลัง'],
  [503, 'CHANNEL_ERROR', 'ช่องทางไม่พร้อมให้บริการ กรุณาลองอีกครั้งภายหลัง'],
] as const)('shows Thai API error %s / %s inside the dialog', async (status, code, message) => {
  const { fetchImpl } = stubFetch(({ method }) => method === 'POST'
    ? { status, body: { error: code, message: 'upstream error' } } : { body: order })
  configureApi({ getAccessToken: () => 't', fetchImpl })
  render(<OrderDetailPage id="o1" me={me} />)
  const dialog = await openCancel()
  fireEvent.change(dialog.getByLabelText('เหตุผล', { exact: true }), { target: { value: 'สินค้าหมด' } })
  fireEvent.click(dialog.getByRole('button', { name: 'ส่งคำขอยกเลิก' }))
  expect(await dialog.findByText(message)).toBeInTheDocument()
  expect(dialog.getByRole('button', { name: 'ส่งคำขอยกเลิก' })).toBeEnabled()
})

it('fifth tab queries pending hold reason, displays its count, preserves dates, and restores from hash', async () => {
  window.location.hash = '#/orders?ordered_from=2026-10-01&ordered_to=2026-10-24'
  const { fetchImpl, calls } = stubFetch(({ url }) => {
    const pending = new URL(url, 'http://test').searchParams.get('hold_reason') === 'CHANNEL_CANCEL_PENDING'
    return { body: { items: pending ? [{ ...order, channel: 'TSF', channel_account_id: 'ca', phone_masked: null }] : [], total: pending ? 3 : 0, limit: 25, next_cursor: null } }
  })
  configureApi({ getAccessToken: () => 't', fetchImpl })
  const { unmount } = render(<OrdersListPage />)
  const tab = screen.getByRole('button', { name: /^รอยกเลิก/ })
  await waitFor(() => expect(tab).toHaveTextContent(/^รอยกเลิก3$/))
  const tabs = within(screen.getByRole('navigation', { name: 'Fulfillment tabs' })).getAllByRole('button')
  expect(tabs).toHaveLength(5)
  expect(tabs[4]).toBe(tab)
  fireEvent.click(tab)
  await screen.findByRole('link', { name: 'ORDER-1' })
  expect(screen.getByLabelText('Hold filter')).toHaveValue('CHANNEL_CANCEL_PENDING')
  expect(screen.getByLabelText('Ordered from')).toHaveValue('2026-10-01')
  expect(screen.getByLabelText('Ordered to')).toHaveValue('2026-10-24')
  await waitFor(() => expect(window.location.hash).toContain('hold_reason=CHANNEL_CANCEL_PENDING'))
  expect(calls.some((c) => c.url.includes('hold_reason=CHANNEL_CANCEL_PENDING') && c.url.includes('ordered_from=2026-10-01') && c.url.includes('ordered_to=2026-10-24'))).toBe(true)
  unmount()
  render(<OrdersListPage />)
  await screen.findByRole('link', { name: 'ORDER-1' })
  expect(screen.getByLabelText('Hold filter')).toHaveValue('CHANNEL_CANCEL_PENDING')
})

it('links the pending hold group to the fifth tab without a detail search constraint', async () => {
  const { fetchImpl } = stubFetch(() => ({ body: { groups: [{ hold_reason: 'CHANNEL_CANCEL_PENDING', hold_detail: 'cancel-id', count: 2, samples: [] }] } }))
  configureApi({ getAccessToken: () => 't', fetchImpl })
  render(<HoldQueuePage />)
  expect(await screen.findByRole('link', { name: 'ดูออเดอร์รอยกเลิก (2)' })).toHaveAttribute('href', '#/orders?hold_reason=CHANNEL_CANCEL_PENDING')
})
