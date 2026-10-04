import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Me } from '../auth/AuthContext'
import AppShell from './AppShell'

vi.mock('../orders/api', () => ({
  ordersApi: {
    list: vi.fn(async () => ({ items: [], total: 0, limit: 1, next_cursor: null })),
  },
}))

vi.mock('../channel/listings/api', () => ({
  listingsApi: {
    listAccounts: vi.fn(async () => ({ items: [{ id: 'ca', channel: 'TSF', external_shop_id: 's', status: 'CONNECTED' }] })),
  },
}))

const owner: Me = {
  tenant: {
    id: '11111111-1111-7111-8111-111111111111',
    name: 'Active Shop',
    tsf_shop_id: 'shop',
    membership_tier: 'PRO',
  },
  role: 'OWNER',
  entitlement: { status: 'ACTIVE', expires_at: null, ent_ver: 1 },
}

const staff: Me = {
  ...owner,
  role: 'STAFF',
}

afterEach(() => cleanup())

describe('AppShell navigation', () => {
  it('shows main catalog links for any signed-in role', () => {
    render(
      <AppShell me={staff} route="#/catalog/skus" userDisplayName={null} onLogout={() => undefined}>
        <p>page</p>
      </AppShell>,
    )
    expect(screen.getByRole('link', { name: 'SKUs' })).toHaveAttribute('href', '#/catalog/skus')
    expect(screen.getByRole('link', { name: 'Orders' })).toHaveAttribute('href', '#/orders')
  })

  it('hides Dead outbox for STAFF but shows for OWNER', () => {
    render(
      <AppShell me={staff} route="#/" userDisplayName={null} onLogout={() => undefined}>
        <p>page</p>
      </AppShell>,
    )
    expect(screen.queryByRole('link', { name: 'Dead outbox' })).not.toBeInTheDocument()

    cleanup()
    render(
      <AppShell me={owner} route="#/" userDisplayName={null} onLogout={() => undefined}>
        <p>page</p>
      </AppShell>,
    )
    expect(screen.getByRole('link', { name: 'Dead outbox' })).toHaveAttribute('href', '#/admin/outbox')
  })
})
