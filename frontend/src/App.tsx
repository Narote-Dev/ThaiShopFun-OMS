import { useEffect, useState } from 'react'
import AuthProvider from './auth/AuthProvider'
import { useAuth } from './auth/useAuth'
import { normalizeHash } from './routing/hash'
import { canWriteCatalog } from './catalog/access'
import ImportPage from './catalog/ImportPage'
import ProductsPage from './catalog/ProductsPage'
import SkuFormPage from './catalog/SkuFormPage'
import SkuListPage from './catalog/SkuListPage'
import OutboxAdminPage from './outbox/OutboxAdminPage'
import AppLayout from './shell/AppLayout'
import DashboardPage from './shell/DashboardPage'
import LoginPage from './shell/LoginPage'
import Paywall from './shell/Paywall'
import RequireSession from './shell/RequireSession'
import SettingUp from './shell/SettingUp'
import WarehousesPage from './warehouse/WarehousesPage'
import type { ReactNode } from 'react'

const SKU_ROUTE = /^#\/catalog\/skus\/([0-9a-f-]{36}|new)$/

// Change: T07 catalog and warehouse pages. Writes need OWNER/ADMIN and a non-GRACE shop.
function page(route: string, canWrite: boolean, readOnly: boolean, name: string): ReactNode {
  if (route === '#/admin/outbox') return <OutboxAdminPage readOnly={readOnly} />
  if (route === '#/') return <DashboardPage name={name} />
  if (route === '#/catalog/skus') return <SkuListPage canWrite={canWrite} />
  if (route === '#/catalog/products') return <ProductsPage canWrite={canWrite} />
  if (route === '#/catalog/import') return <ImportPage canWrite={canWrite} />
  if (route === '#/warehouses') return <WarehousesPage canWrite={canWrite} />
  const sku = SKU_ROUTE.exec(route)
  if (sku) {
    const id = sku[1] === 'new' ? null : sku[1]
    return <SkuFormPage key={sku[1]} id={id} canWrite={canWrite} />
  }
  return (
    <main>
      <h1>Not found</h1>
    </main>
  )
}

function useRoute(): string {
  const [route, setRoute] = useState(() => normalizeHash(window.location.hash))
  useEffect(() => {
    const sync = () => setRoute(normalizeHash(window.location.hash))
    window.addEventListener('hashchange', sync)
    return () => window.removeEventListener('hashchange', sync)
  }, [])
  return route
}

function Shell() {
  const auth = useAuth()
  const route = useRoute()
  if (auth.status === 'booting') {
    return (
      <main>
        <p role="status">Signing in…</p>
      </main>
    )
  }
  const signedIn = auth.status === 'signed-in'
  let body = (
    <main>
      <p role="status">Loading shop…</p>
    </main>
  )
  if (auth.gate === 'paywall') {
    body = <Paywall onLogout={auth.signOut} />
  } else if (auth.gate === 'setting-up' && !auth.me) {
    body = (
      <SettingUp
        message={auth.setupMessage ?? 'Your shop is being set up.'}
        onRetry={auth.setupFailed ? auth.retrySetup : null}
      />
    )
  } else if (auth.profileError) {
    body = (
      <main>
        <p role="alert">{auth.profileError}</p>
        <button type="button" onClick={auth.signOut}>
          Log out
        </button>
      </main>
    )
  } else if (auth.me) {
    const readOnly = auth.me.entitlement.status === 'GRACE'
    const canWrite = canWriteCatalog(auth.me)
    body = (
      <AppLayout me={auth.me} readOnlyNotice={auth.readOnlyNotice} onLogout={auth.signOut}>
        {page(route, canWrite, readOnly, auth.me.tenant.name)}
      </AppLayout>
    )
  }
  return (
    <RequireSession signedIn={signedIn} fallback={<LoginPage error={auth.signInError} onSignIn={auth.signIn} />}>
      {body}
    </RequireSession>
  )
}

export default function App() {
  return (
    <AuthProvider>
      <Shell />
    </AuthProvider>
  )
}
