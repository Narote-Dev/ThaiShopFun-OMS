import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { configureApi, resetApiForTests } from '../api/client'
import type { Me } from '../auth/AuthContext'
import { stubFetch } from '../catalog/testFetch'
import { READ_ONLY_ACCESS, stockAccess } from './access'
import StockDocumentPage from './StockDocumentPage'
import StockDocumentsPage from './StockDocumentsPage'
import StockHistoryPage from './StockHistoryPage'

const DOC_ID = '0190bbbb-0000-7000-8000-000000000001'
const SKU_ID = '0190aaaa-0000-7000-8000-000000000001'
const WH = 'wh-1'

const warehouses = { items: [{ id: WH, code: 'MAIN', name: 'Main', address: null, is_default: true }] }

const line = (overrides: Record<string, unknown> = {}) => ({
  id: 'line-1',
  sku_id: SKU_ID,
  sku_code: 'MUG-RED',
  sku_name: 'Red mug',
  warehouse_id: WH,
  warehouse_code: 'MAIN',
  qty: -2,
  system_qty_at_start: null,
  counted_qty: null,
  reason_code: 'LOST',
  on_hand: 5,
  reserved: 4,
  ...overrides,
})

const doc = (overrides: Record<string, unknown> = {}) => ({
  id: DOC_ID,
  type: 'ADJUSTMENT',
  status: 'DRAFT',
  reference_no: 'ADJ-1',
  note: null,
  count_started_at: null,
  posted_at: null,
  posted_by: null,
  line_count: 1,
  warehouse_ids: [WH],
  created_at: '2026-09-30T03:00:00Z',
  updated_at: '2026-09-30T03:00:00Z',
  lines: [line()],
  ...overrides,
})

const me = (role: string, status = 'ACTIVE'): Me => ({
  tenant: { id: 't', name: 'Shop', tsf_shop_id: 's', membership_tier: 'PRO' },
  role,
  entitlement: { status, expires_at: null, ent_ver: 1 },
})

beforeEach(() => {
  resetApiForTests()
})

afterEach(() => {
  cleanup()
  resetApiForTests()
  vi.restoreAllMocks()
  window.location.hash = ''
})

describe('stockAccess', () => {
  it('lets any member post RECEIVE, only managers post the rest or void, and nobody in GRACE', () => {
    const staff = stockAccess(me('STAFF'))
    expect(staff.canEdit).toBe(true)
    expect(staff.canPost('RECEIVE')).toBe(true)
    expect(['OPENING', 'ADJUSTMENT', 'COUNT', 'WRITE_OFF'].some((t) => staff.canPost(t as 'COUNT'))).toBe(false)
    expect(staff.canVoid).toBe(false)
    const admin = stockAccess(me('ADMIN'))
    expect(admin.canPost('ADJUSTMENT')).toBe(true)
    expect(admin.canVoid).toBe(true)
    const grace = stockAccess(me('OWNER', 'GRACE'))
    expect(grace.canEdit || grace.canVoid || grace.canPost('RECEIVE')).toBe(false)
  })
})

describe('StockDocumentsPage', () => {
  it('filters the list and creates a draft that opens in the editor', async () => {
    const { fetchImpl, calls } = stubFetch(({ url, method }) => {
      if (method === 'POST') return { status: 201, body: doc({ id: 'new-doc', type: 'RECEIVE', lines: [] }) }
      if (url.startsWith('/api/v1/stock-documents')) return { body: { items: [doc()], total: 1, limit: 25, offset: 0 } }
      return undefined
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<StockDocumentsPage access={stockAccess(me('STAFF'))} />)
    expect(await screen.findByText('ADJ-1')).toBeInTheDocument()

    const filters = screen.getByRole('form', { name: 'ตัวกรองเอกสารสต็อก' })
    fireEvent.change(within(filters).getByLabelText('สถานะ'), { target: { value: 'POSTED' } })
    fireEvent.change(within(filters).getByLabelText('ตั้งแต่'), { target: { value: '2026-09-01' } })
    fireEvent.click(within(filters).getByRole('button', { name: 'กรอง' }))
    await waitFor(() => expect(calls.some((c) => c.url.includes('status=POSTED') && c.url.includes('from=2026-09-01'))).toBe(true))

    fireEvent.change(screen.getByLabelText('ประเภทเอกสารใหม่'), { target: { value: 'RECEIVE' } })
    fireEvent.change(screen.getByLabelText('เลขที่อ้างอิง'), { target: { value: 'PO-9' } })
    fireEvent.click(screen.getByRole('button', { name: 'สร้างเอกสาร' }))
    await waitFor(() => expect(window.location.hash).toBe('#/stock/documents/new-doc'))
    const post = calls.find((c) => c.method === 'POST')
    expect(JSON.parse(String(post?.init?.body))).toEqual({ type: 'RECEIVE', reference_no: 'PO-9', note: null })
  })

  it('hides the create form when the shop is read-only', async () => {
    const { fetchImpl } = stubFetch(() => ({ body: { items: [], total: 0, limit: 25, offset: 0 } }))
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<StockDocumentsPage access={READ_ONLY_ACCESS} />)
    expect(await screen.findByText('ยังไม่มีเอกสารสต็อก')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'สร้างเอกสาร' })).toBeNull()
  })
})

describe('StockDocumentPage', () => {
  it.each([
    ['ลงบัญชี', 'DRAFT', 'ลงบัญชีเอกสารสต็อก'],
    ['ลบฉบับร่าง', 'DRAFT', 'ลบเอกสารฉบับร่าง'],
    ['ยกเลิกเอกสาร', 'POSTED', 'ยกเลิกเอกสารสต็อก'],
  ])('cancels %s through Dialog without an API mutation', async (button, status, title) => {
    const { fetchImpl, calls } = stubFetch(({ url }) => {
      if (url === '/api/v1/warehouses') return { body: warehouses }
      return { body: doc({ status }) }
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<StockDocumentPage id={DOC_ID} access={stockAccess(me('OWNER'))} />)
    fireEvent.click(await screen.findByRole('button', { name: button }))
    expect(screen.getByRole('dialog', { name: title })).toBeInTheDocument()
    expect(calls.filter((call) => call.method !== 'GET')).toHaveLength(0)
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'ยกเลิก' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(calls.filter((call) => call.method !== 'GET')).toHaveLength(0)
  })

  it('adds a line by SKU code and shows BELOW_RESERVED on the line that caused it', async () => {
    const { fetchImpl, calls } = stubFetch(({ url, method }) => {
      if (url === '/api/v1/warehouses') return { body: warehouses }
      if (url.startsWith('/api/v1/skus')) return { body: { items: [], total: 0, limit: 10, offset: 0 } }
      if (url.endsWith('/lines') && method === 'POST') return { status: 201, body: line({ id: 'line-2' }) }
      if (url.endsWith('/post'))
        return {
          status: 422,
          body: {
            error: 'BELOW_RESERVED',
            message: '1 row(s) would go below reserved',
            errors: [
              {
                line_id: 'line-1',
                sku_id: SKU_ID,
                warehouse_id: WH,
                error: 'BELOW_RESERVED',
                message: 'on_hand 5 + -2 would be below reserved 4',
                on_hand: 5,
                reserved: 4,
                delta: -2,
              },
            ],
          },
        }
      if (url === `/api/v1/stock-documents/${DOC_ID}`) return { body: doc() }
      return undefined
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<StockDocumentPage id={DOC_ID} access={stockAccess(me('OWNER'))} />)
    expect(await screen.findByText('ปรับสต็อก · ฉบับร่าง')).toBeInTheDocument()

    // Step 1: Add a line: the body carries the code, warehouse, qty, and reason.
    const add = screen.getByRole('form', { name: 'เพิ่มรายการ' })
    fireEvent.change(within(add).getByLabelText('รหัสสินค้า'), { target: { value: 'MUG-RED' } })
    fireEvent.change(within(add).getByLabelText('จำนวน'), { target: { value: '3' } })
    fireEvent.change(within(add).getByLabelText('เหตุผล'), { target: { value: 'FOUND' } })
    fireEvent.click(within(add).getByRole('button', { name: 'เพิ่มรายการ' }))
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/lines') && c.method === 'POST')).toBe(true))
    const added = calls.find((c) => c.url.endsWith('/lines') && c.method === 'POST')
    expect(JSON.parse(String(added?.init?.body))).toEqual({ warehouse_id: WH, qty: 3, reason_code: 'FOUND', sku_code: 'MUG-RED' })

    // Step 2: Post after confirming; the per-line problem appears in that row.
    fireEvent.click(await screen.findByRole('button', { name: 'ลงบัญชี' }))
    fireEvent.click(within(screen.getByRole('dialog', { name: 'ลงบัญชีเอกสารสต็อก' })).getByRole('button', { name: 'ยืนยัน' }))
    const lines = screen.getByRole('table', { name: 'รายการ' })
    expect(await within(lines).findByRole('alert')).toHaveTextContent('คงเหลือ 5 ปรับ -2 จะต่ำกว่ายอดจอง 4')
  })

  it('hides Post from STAFF on an adjustment and everything in a read-only shop', async () => {
    const { fetchImpl } = stubFetch(({ url }) => {
      if (url === '/api/v1/warehouses') return { body: warehouses }
      if (url === `/api/v1/stock-documents/${DOC_ID}`) return { body: doc() }
      return undefined
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    const { unmount } = render(<StockDocumentPage id={DOC_ID} access={stockAccess(me('STAFF'))} />)
    expect(await screen.findByText('เฉพาะเจ้าของร้านหรือผู้ดูแลระบบเท่านั้นที่ลงบัญชีเอกสารนี้ได้')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'ลงบัญชี' })).toBeNull()
    expect(screen.getByRole('button', { name: 'เพิ่มรายการ' })).toBeInTheDocument()
    unmount()

    render(<StockDocumentPage id={DOC_ID} access={READ_ONLY_ACCESS} />)
    expect(await screen.findByText('ร้านค้านี้อ่านได้อย่างเดียว')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'ลงบัญชี' })).toBeNull()
    expect(screen.queryByRole('form', { name: 'เพิ่มรายการ' })).toBeNull()
  })

  it('starts a count, edits the counted column, and voids a posted document after confirming', async () => {
    let current = doc({ type: 'COUNT', lines: [line({ qty: 0, reason_code: null, counted_qty: null })] })
    const { fetchImpl, calls } = stubFetch(({ url, method }) => {
      if (url === '/api/v1/warehouses') return { body: warehouses }
      if (url.endsWith('/start-count')) {
        current = doc({
          type: 'COUNT',
          count_started_at: '2026-09-30T04:00:00Z',
          lines: [line({ qty: 0, reason_code: null, system_qty_at_start: 10 })],
        })
        return { body: current }
      }
      if (url.endsWith('/lines/line-1') && method === 'PUT') return { body: line({ counted_qty: 9 }) }
      if (url.endsWith('/void')) return { body: { document_id: DOC_ID, type: 'COUNT', status: 'VOID', movements: [{}] } }
      if (url === `/api/v1/stock-documents/${DOC_ID}`) return { body: current }
      return undefined
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    const { unmount } = render(<StockDocumentPage id={DOC_ID} access={stockAccess(me('OWNER'))} />)

    fireEvent.click(await screen.findByRole('button', { name: 'เริ่มตรวจนับ' }))
    expect(await screen.findByText('10')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'เริ่มตรวจนับ' })).toBeNull()
    fireEvent.change(screen.getByLabelText('ตรวจนับแล้ว สำหรับ MUG-RED'), { target: { value: '9' } })
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
    const put = calls.find((c) => c.method === 'PUT')
    expect(JSON.parse(String(put?.init?.body))).toEqual({ warehouse_id: WH, counted_qty: 9, sku_id: SKU_ID })
    unmount()

    current = doc({ type: 'COUNT', status: 'POSTED', posted_at: '2026-09-30T05:00:00Z' })
    render(<StockDocumentPage id={DOC_ID} access={stockAccess(me('ADMIN'))} />)
    fireEvent.click(await screen.findByRole('button', { name: 'ยกเลิกเอกสาร' }))
    fireEvent.click(within(screen.getByRole('dialog', { name: 'ยกเลิกเอกสารสต็อก' })).getByRole('button', { name: 'ยืนยัน' }))
    expect(await screen.findByRole('status')).toHaveTextContent('ยกเลิกแล้ว บันทึกรายการกลับบัญชี 1 รายการ')
  })
})

describe('StockHistoryPage', () => {
  const entry = (overrides: Record<string, unknown> = {}) => ({
    id: 'e-1',
    created_at: '2026-09-30T03:00:00Z',
    warehouse_id: WH,
    warehouse_code: 'MAIN',
    reason: 'RECEIVE',
    delta_on_hand: 5,
    delta_reserved: 0,
    on_hand_after: 15,
    reserved_after: 0,
    actor: 'user:u-1',
    ref_type: 'stock_document_line',
    ref_id: 'line-1',
    link: { kind: 'stock_document', document_id: DOC_ID, document_type: 'RECEIVE', reference_no: 'PO-1' },
    ...overrides,
  })

  it('links documents, shows order refs, filters by reason, and loads more with the cursor', async () => {
    const { fetchImpl, calls } = stubFetch(({ url }) => {
      if (url === '/api/v1/warehouses') return { body: warehouses }
      if (url.includes('cursor=c-2'))
        return { body: { sku: { id: SKU_ID, sku_code: 'MUG-RED', name: 'Red mug' }, items: [entry({ id: 'e-3', reason: 'OPENING_BALANCE' })], next_cursor: null } }
      if (url.includes('/stock-history'))
        return {
          body: {
            sku: { id: SKU_ID, sku_code: 'MUG-RED', name: 'Red mug' },
            items: [
              entry(),
              entry({
                id: 'e-2',
                reason: 'SHIP',
                delta_on_hand: -1,
                delta_reserved: -1,
                ref_type: 'stock_reservation',
                link: { kind: 'reservation', reservation_group_id: 'g-1', owner_type: 'ORDER', order_ref: 'ord-77' },
              }),
            ],
            next_cursor: 'c-2',
          },
        }
      return undefined
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<StockHistoryPage skuId={SKU_ID} />)

    expect(await screen.findByRole('link', { name: 'รับเข้า PO-1' })).toHaveAttribute('href', `#/stock/documents/${DOC_ID}`)
    expect(screen.getByText('ออเดอร์ ord-77')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'ประวัติสต็อก · MUG-RED' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'โหลดเพิ่มเติม' }))
    expect(await screen.findByText('ยอดยกมา')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'โหลดเพิ่มเติม' })).toBeNull()

    fireEvent.change(screen.getByLabelText('เหตุผล'), { target: { value: 'SHIP' } })
    fireEvent.change(screen.getByLabelText('ถึง'), { target: { value: '2026-09-30' } })
    fireEvent.click(screen.getByRole('button', { name: 'กรอง' }))
    await waitFor(() => expect(calls.some((c) => c.url.includes('reason=SHIP') && c.url.includes('to=2026-09-30'))).toBe(true))
  })

  it('shows the bundle hint from the server', async () => {
    const { fetchImpl } = stubFetch(({ url }) => {
      if (url === '/api/v1/warehouses') return { body: warehouses }
      return { status: 422, body: { error: 'BUNDLE_NOT_STOCKABLE', message: 'A bundle has no stock of its own' } }
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<StockHistoryPage skuId={SKU_ID} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('สินค้าชุดไม่มีสต็อกของตัวเอง')
  })
})
