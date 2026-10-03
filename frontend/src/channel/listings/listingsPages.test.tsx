import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { configureApi, resetApiForTests } from '../../api/client'
import type { Me } from '../../auth/AuthContext'
import { stubFetch } from '../../catalog/testFetch'
import ListingsPage from './ListingsPage'

const owner: Me = {
  tenant: { id: 't1', name: 'Shop', membership_tier: 'PRO', tsf_shop_id: 'shop' },
  role: 'OWNER',
  entitlement: { status: 'ACTIVE', expires_at: null, ent_ver: 1 },
}

const staff: Me = { ...owner, role: 'STAFF' }

beforeEach(() => {
  resetApiForTests()
  window.location.hash = '#/channel/listings?channel_account_id=ca-1&mapped=false'
})

afterEach(() => {
  cleanup()
  resetApiForTests()
  vi.restoreAllMocks()
})

describe('ListingsPage', () => {
  it('maps filters to query string and hides write controls for STAFF', async () => {
    const { fetchImpl, calls } = stubFetch(({ url, init }) => {
      if (url.includes('/channel-listings') && !init?.method) {
        return {
          body: {
            items: [
              {
                id: 'l1',
                channel_account_id: 'ca-1',
                external_sku_id: 'L-x',
                seller_sku: 'SKU-X',
                name: 'Item',
                sku_id: null,
                mapping_source: null,
                mapped_at: null,
                stock_control: true,
                held_orders: 2,
              },
            ],
            total: 1,
            limit: 25,
            offset: 0,
          },
        }
      }
      return { body: { items: [], total: 0, limit: 10, offset: 0 } }
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<ListingsPage me={staff} />)
    await screen.findByText('L-x')
    expect(screen.queryByRole('button', { name: 'Sync listings' })).toBeNull()
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('mapped=false'))).toBe(true),
    )
  })

  it('shows re-evaluation summary after save for OWNER', async () => {
    const { fetchImpl } = stubFetch(({ url, init }) => {
      if (url.includes('/mapping') && init?.method === 'PUT') {
        return {
          body: {
            listing: {
              id: 'l1',
              channel_account_id: 'ca-1',
              external_sku_id: 'L-x',
              seller_sku: 'SKU-X',
              name: 'Item',
              sku_id: 'sku-1',
              mapping_source: 'MANUAL',
              mapped_at: '2026-01-01T00:00:00Z',
              stock_control: true,
              held_orders: 0,
            },
            reevaluation: { released: 1, out_of_stock: 0, still_held: 0, deferred: 0 },
          },
        }
      }
      if (url.includes('/skus?')) {
        return {
          body: {
            items: [{ id: 'sku-1', sku_code: 'DEMO', name: 'Demo', product_id: 'p', product_name: 'P', barcode: null, weight_g: null, is_bundle: false, on_hand: 1, reserved: 0, component_count: 0 }],
            total: 1,
            limit: 10,
            offset: 0,
          },
        }
      }
      return {
        body: {
          items: [
            {
              id: 'l1',
              channel_account_id: 'ca-1',
              external_sku_id: 'L-x',
              seller_sku: 'SKU-X',
              name: 'Item',
              sku_id: null,
              mapping_source: null,
              mapped_at: null,
              stock_control: true,
              held_orders: 2,
            },
          ],
          total: 1,
          limit: 25,
          offset: 0,
        },
      }
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<ListingsPage me={owner} />)
    await screen.findByText('L-x')
    fireEvent.click(screen.getByRole('button', { name: 'Map' }))
    fireEvent.change(screen.getByLabelText('SKU search'), { target: { value: 'DEMO' } })
    await screen.findByRole('button', { name: /DEMO/ })
    fireEvent.click(screen.getByRole('button', { name: /DEMO/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Save mapping' }))
    await screen.findByText(/released 1/)
  })
})
