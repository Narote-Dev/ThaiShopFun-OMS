import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { configureApi, resetApiForTests } from '../api/client'
import type { Me } from '../auth/AuthContext'
import HoldQueuePage from './HoldQueuePage'
import OrderDetailPage from './OrderDetailPage'
import OrdersListPage from './OrdersListPage'
import { ORDER_PAGE_SIZE } from './api'
import { stubFetch } from '../catalog/testFetch'

const me: Me = {
  tenant: { id: 't1', name: 'Shop', membership_tier: 'PRO', tsf_shop_id: 'shop' },
  role: 'OWNER',
  entitlement: { status: 'ACTIVE', expires_at: null, ent_ver: 1 },
}

const listItem = (overrides: Record<string, unknown> = {}) => ({
  id: 'o-1',
  external_order_id: 'ORD-1',
  order_status: 'ACTIVE',
  payment_status: 'PAID',
  fulfillment_status: 'READY_TO_PICK',
  hold_reason: 'NONE',
  payment_method: 'PREPAID',
  grand_total: '100',
  ordered_at: '2026-01-01T00:00:00Z',
  ship_by: null,
  channel_account_id: 'ca-1',
  channel: 'TSF',
  phone_masked: '***-***-5678',
  ...overrides,
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

describe('OrdersListPage', () => {
  it('maps filters to query string and paginates with cursor', async () => {
    const { fetchImpl, calls } = stubFetch(({ url }) => {
      const parsed = new URL(url, 'http://x')
      const cursor = parsed.searchParams.get('cursor')
      if (cursor === 'page-2') {
        return {
          body: {
            items: [listItem({ id: 'o-2', external_order_id: 'ORD-2' })],
            total: 2,
            limit: ORDER_PAGE_SIZE,
            next_cursor: null,
          },
        }
      }
      return {
        body: {
          items: [listItem()],
          total: 2,
          limit: ORDER_PAGE_SIZE,
          next_cursor: 'page-2',
        },
      }
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<OrdersListPage />)
    expect(await screen.findByRole('link', { name: 'ORD-1' })).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Search'), { target: { value: 'ORD' } })
    fireEvent.change(screen.getByLabelText('Hold'), { target: { value: 'CHANNEL_CANCEL_PENDING' } })
    fireEvent.change(screen.getByLabelText('Order status'), { target: { value: 'ACTIVE' } })
    fireEvent.change(screen.getByLabelText('Payment status'), { target: { value: 'PAID' } })
    fireEvent.change(screen.getByLabelText('Channel'), { target: { value: 'TSF' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    await waitFor(() =>
      expect(
        calls.some(
          (c) =>
            c.url.includes('q=ORD') &&
            c.url.includes('hold_reason=CHANNEL_CANCEL_PENDING') &&
            c.url.includes('order_status=ACTIVE') &&
            c.url.includes('payment_status=PAID') &&
            c.url.includes('channel=TSF'),
        ),
      ).toBe(true),
    )

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByRole('link', { name: 'ORD-2' })).toBeInTheDocument()
    expect(calls.at(-1)?.url).toContain('cursor=page-2')
  })
})

describe('OrderDetailPage', () => {
  it('renders badges, hold banner, timeline, masked phone, and related sections', async () => {
    const detail = {
      id: 'o-1',
      external_order_id: 'ORD-HOLD',
      order_status: 'ACTIVE',
      payment_status: 'PAID',
      fulfillment_status: 'UNFULFILLED',
      hold_reason: 'SKU_NOT_MAPPED',
      hold_detail: 'L-missing',
      hold_note: 'listing not mapped',
      payment_method: 'COD',
      currency: 'THB',
      grand_total: '250',
      ordered_at: '2026-01-01T00:00:00Z',
      paid_at: null,
      ship_by: null,
      version: 1,
      supports_cancel_request: true,
      channel_account: { id: 'ca', channel: 'TSF', mode: 'ACTIVE', status: 'CONNECTED' },
      lines: [
        {
          id: 'l1',
          external_line_id: 'L1',
          external_sku_id: 'X',
          sku_id: 'sku-1',
          sku_code: 'SKU-1',
          name: 'Item',
          qty: 1,
          unit_price: '250',
          mapped: true,
          bundle: true,
          components: [{ sku_id: 'c1', sku_code: 'COMP', name: 'Comp', qty: 2 }],
        },
      ],
      reservations: [
        {
          id: 'r1',
          sku_id: 'sku-1',
          sku_code: 'SKU-1',
          warehouse_id: 'w1',
          warehouse_code: 'MAIN',
          qty: 1,
          status: 'ACTIVE',
          expires_at: null,
        },
      ],
      shipments: [{ id: 's1', tracking_no: 'TH123', carrier: 'Kerry', status: 'LABELLED', shipped_at: null }],
      recipient: {
        name_masked: 'ส***',
        phone_masked: '***-***-9999',
        province: 'Bangkok',
        postcode: '10110',
        pii_status: 'ACTIVE',
      },
      timeline: [
        {
          dimension: 'fulfillment_status',
          from_value: 'UNFULFILLED',
          to_value: 'READY_TO_PICK',
          reason: null,
          actor: 'system',
          at: '2026-01-01T01:00:00Z',
        },
      ],
    }
    const { fetchImpl } = stubFetch(() => ({ body: detail }))
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<OrderDetailPage id="o-1" me={me} />)
    expect(await screen.findByRole('heading', { name: 'Order ORD-HOLD' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Hold: SKU_NOT_MAPPED (L-missing)')
    expect(screen.getByText(/\*\*\*-\*\*\*-9999/)).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Order lines' })).toBeInTheDocument()
    expect(screen.getByText('COMP')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Reservations' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Shipments' })).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Status timeline' })).toHaveTextContent('fulfillment_status')
    expect(screen.getByRole('button', { name: 'Re-check hold' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Request cancel' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'buyer changed mind' } })
    expect(screen.getByRole('button', { name: 'Request cancel' })).toBeEnabled()
  })
})

describe('HoldQueuePage', () => {
  it('lists hold groups with sample links', async () => {
    const { fetchImpl } = stubFetch(() => ({
      body: {
        groups: [
          {
            hold_reason: 'OUT_OF_STOCK',
            hold_detail: null,
            count: 2,
            samples: [{ id: 'o-h', external_order_id: 'DEMO-OOS', ordered_at: '2026-01-01T00:00:00Z' }],
          },
        ],
      },
    }))
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<HoldQueuePage />)
    expect(await screen.findByRole('link', { name: 'DEMO-OOS' })).toHaveAttribute('href', '#/orders/o-h')
    expect(screen.getByRole('table', { name: 'Hold groups' })).toHaveTextContent('OUT_OF_STOCK')
  })
})
