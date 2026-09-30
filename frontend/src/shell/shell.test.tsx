import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Me } from '../auth/AuthContext'
import AppLayout from './AppLayout'
import GraceBanner from './GraceBanner'
import LoginPage from './LoginPage'
import Paywall from './Paywall'
import RequireSession from './RequireSession'

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
      <AppLayout me={me} readOnlyNotice={null} onLogout={() => undefined}>
        <button type="button" disabled>
          Retry
        </button>
      </AppLayout>,
    )
    expect(screen.getByRole('status')).toHaveTextContent('read-only until 2026-10-30')
    expect(screen.getByText('Grace Shop')).toBeInTheDocument()
    expect(screen.getByText('OWNER')).toBeInTheDocument()
    expect(screen.getByText('PRO · GRACE')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeDisabled()
  })

  it('renders a grace expiry on its own', () => {
    render(<GraceBanner expiresAt="2026-10-30T00:00:00Z" />)
    expect(screen.getByRole('status')).toHaveTextContent('2026-10-30')
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
