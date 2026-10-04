import { useEffect, useState } from 'react'
import AuthProvider from './auth/AuthProvider'
import { useAuth } from './auth/useAuth'
import { normalizeHash } from './routing/hash'
import type { Me } from './auth/AuthContext'
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
import { stockAccess } from './stock/access'
import StockDocumentPage from './stock/StockDocumentPage'
import StockDocumentsPage from './stock/StockDocumentsPage'
import StockHistoryPage from './stock/StockHistoryPage'
import WarehousesPage from './warehouse/WarehousesPage'
import HoldQueuePage from './orders/HoldQueuePage'
import OrderDetailPage from './orders/OrderDetailPage'
import OrdersListPage from './orders/OrdersListPage'
import ListingsPage from './channel/listings/ListingsPage'
import { Button } from './ui/Button'
import { PageContent } from './ui/PageContent'
import { PageHeader } from './ui/PageHeader'
import type { ReactNode } from 'react'

const SKU_ROUTE = /^#\/catalog\/skus\/([0-9a-f-]{36}|new)$/
const SKU_HISTORY_ROUTE = /^#\/catalog\/skus\/([0-9a-f-]{36})\/history$/
const DOCUMENT_ROUTE = /^#\/stock\/documents\/([0-9a-f-]{36})$/
const ORDER_ROUTE = /^#\/orders\/([0-9a-f-]{36})$/
const ORDERS_ROUTE = /^#\/orders(\?.*)?$/
const LISTINGS_ROUTE = /^#\/channel\/listings(\?.*)?$/

// Change: T07 catalog and warehouse pages. Writes need OWNER/ADMIN and a non-GRACE shop.
// Change: T08A stock documents and history. Drafts: any member; post/void rules in stockAccess.
function page(route: string, me: Me): ReactNode {
  const canWrite = canWriteCatalog(me)
  const readOnly = me.entitlement.status === 'GRACE'
  if (route === '#/admin/outbox') return <OutboxAdminPage readOnly={readOnly} />
  if (route === '#/') return <DashboardPage name={me.tenant.name} />
  if (route === '#/stock/documents') return <StockDocumentsPage access={stockAccess(me)} />
  const document = DOCUMENT_ROUTE.exec(route)
  if (document) return <StockDocumentPage key={document[1]} id={document[1]} access={stockAccess(me)} />
  const history = SKU_HISTORY_ROUTE.exec(route)
  if (history) return <StockHistoryPage key={history[1]} skuId={history[1]} />
  if (route === '#/catalog/skus') return <SkuListPage canWrite={canWrite} />
  if (route === '#/catalog/products') return <ProductsPage canWrite={canWrite} />
  if (route === '#/catalog/import') return <ImportPage canWrite={canWrite} />
  if (route === '#/warehouses') return <WarehousesPage canWrite={canWrite} />
  if (route === '#/orders/holds') return <HoldQueuePage />
  if (LISTINGS_ROUTE.test(route)) return <ListingsPage me={me} />
  if (ORDERS_ROUTE.test(route)) return <OrdersListPage />
  const order = ORDER_ROUTE.exec(route.split('?')[0])
  if (order) return <OrderDetailPage key={order[1]} id={order[1]} me={me} />
  const sku = SKU_ROUTE.exec(route)
  if (sku) {
    const id = sku[1] === 'new' ? null : sku[1]
    return <SkuFormPage key={sku[1]} id={id} canWrite={canWrite} />
  }
  return (
    <PageContent>
      <PageHeader title="Not found" />
    </PageContent>
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
      <PageContent>
        <p role="status" className="text-[13px] text-stone-600">
          Signing in…
        </p>
      </PageContent>
    )
  }
  const signedIn = auth.status === 'signed-in'
  let body = (
    <PageContent>
      <p role="status" className="text-[13px] text-stone-600">
        Loading shop…
      </p>
    </PageContent>
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
      <PageContent>
        <p role="alert" className="text-[13px] text-red-700">
          {auth.profileError}
        </p>
        <Button type="button" variant="secondary" className="mt-4" onClick={auth.signOut}>
          Log out
        </Button>
      </PageContent>
    )
  } else if (auth.me) {
    body = (
      <AppLayout me={auth.me} route={route} readOnlyNotice={auth.readOnlyNotice} onLogout={auth.signOut}>
        {page(route, auth.me)}
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
