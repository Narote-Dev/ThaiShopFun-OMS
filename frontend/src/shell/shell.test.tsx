import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Me } from '../auth/AuthContext'
import AppLayout from './AppLayout'
import GraceBanner from './GraceBanner'
import LoginPage from './LoginPage'
import Paywall from './Paywall'
import RequireSession from './RequireSession'
import SettingUp from './SettingUp'
import { canWriteCatalog } from '../catalog/access'

const me: Me = {
  tenant: {
    id: '11111111-1111-7111-8111-111111111111',
    name: 'Grace Shop',
    tsf_shop_id: 'shop_grace',
    membership_tier: 'PRO',
  },
  role: 'OWNER',
  entitlement: { status: 'GRACE', expires_at: '2026-10-30T00:00:00Z', ent_ver: 1 },
}

afterEach(() => {
  cleanup()
})

describe('route guard', () => {
  it('sends a signed-out user to the login screen', () => {
    render(
      <RequireSession signedIn={false} fallback={<LoginPage error={null} onSignIn={() => undefined} />}>
        <h1>Active Shop</h1>
      </RequireSession>,
    )
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Active Shop' })).not.toBeInTheDocument()
  })

  it('renders the page the user asked for once they are signed in', () => {
    render(
      <RequireSession signedIn fallback={<LoginPage error={null} onSignIn={() => undefined} />}>
        <h1>Dead outbox</h1>
      </RequireSession>,
    )
    expect(screen.getByRole('heading', { name: 'Dead outbox' })).toBeInTheDocument()
  })
})

describe('paywall and grace', () => {
  it('shows the paywall without shop data', () => {
    render(<Paywall onLogout={() => undefined} />)
    expect(screen.getByRole('heading', { name: 'Membership needed' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'TSF Seller Center' })).toHaveAttribute(
      'href',
      'https://seller.thaishopfun.example/membership',
    )
    expect(screen.getByRole('button', { name: 'Log out' })).toBeInTheDocument()
    expect(screen.queryByText('Active Shop')).not.toBeInTheDocument()
    expect(screen.queryByText('Expired Shop')).not.toBeInTheDocument()
  })

  it('shows the grace banner with the expiry date and keeps write actions off', () => {
    render(
      <AppLayout me={me} route="#/" userDisplayName={null} readOnlyNotice={null} onLogout={() => undefined}>
        <button type="button" disabled>
          Retry
        </button>
      </AppLayout>,
    )
    expect(screen.getByRole('status')).toHaveTextContent('read-only until 2026-10-30')
    expect(screen.getAllByText('Grace Shop').length).toBeGreaterThan(0)
    expect(screen.getByText('OWNER')).toBeInTheDocument()
    expect(screen.getByText('PRO · GRACE')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeDisabled()
  })

  it('renders a grace expiry on its own', () => {
    render(<GraceBanner expiresAt="2026-10-30T00:00:00Z" />)
    expect(screen.getByRole('status')).toHaveTextContent('2026-10-30')
  })
})

describe('setting up', () => {
  it('shows the setup message once and a Retry button after retries stop', () => {
    render(<SettingUp message="Shop is not registered yet" onRetry={() => undefined} />)
    expect(screen.getAllByText('Shop is not registered yet')).toHaveLength(1)
    expect(screen.queryByText(/Retrying/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})

describe('login', () => {
  it('starts sign-in from the signed-out screen', () => {
    const onSignIn = vi.fn()
    render(<LoginPage error={null} onSignIn={onSignIn} />)
    screen.getByRole('button', { name: 'Sign in' }).click()
    expect(onSignIn).toHaveBeenCalledTimes(1)
  })
})

describe('catalog navigation and write access', () => {
  it('links the catalog and warehouse pages from the shell', () => {
    render(
      <AppLayout
        me={me}
        route="#/catalog/skus"
        userDisplayName={null}
        readOnlyNotice={null}
        onLogout={() => undefined}
      >
        <p>page</p>
      </AppLayout>,
    )
    for (const [name, href] of [
      ['SKUs', '#/catalog/skus'],
      ['Products', '#/catalog/products'],
      ['Import', '#/catalog/import'],
      ['Warehouses', '#/warehouses'],
    ]) {
      expect(screen.getByRole('link', { name })).toHaveAttribute('href', href)
    }
  })

  it('allows writes for OWNER and ADMIN only, never in GRACE', () => {
    const active = { ...me, entitlement: { ...me.entitlement, status: 'ACTIVE' } }
    expect(canWriteCatalog({ ...active, role: 'OWNER' })).toBe(true)
    expect(canWriteCatalog({ ...active, role: 'ADMIN' })).toBe(true)
    expect(canWriteCatalog({ ...active, role: 'STAFF' })).toBe(false)
    expect(canWriteCatalog(me)).toBe(false)
  })
})
