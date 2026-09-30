import { useEffect, useState } from 'react'
import AuthProvider from './auth/AuthProvider'
import { useAuth } from './auth/useAuth'
import { normalizeHash } from './routing/hash'
import OutboxAdminPage from './outbox/OutboxAdminPage'
import AppLayout from './shell/AppLayout'
import DashboardPage from './shell/DashboardPage'
import LoginPage from './shell/LoginPage'
import Paywall from './shell/Paywall'
import RequireSession from './shell/RequireSession'
import SettingUp from './shell/SettingUp'

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
    body = (
      <AppLayout me={auth.me} readOnlyNotice={auth.readOnlyNotice} onLogout={auth.signOut}>
        {route === '#/admin/outbox' ? (
          <OutboxAdminPage readOnly={auth.me.entitlement.status === 'GRACE'} />
        ) : route === '#/' ? (
          <DashboardPage name={auth.me.tenant.name} />
        ) : (
          <main>
            <h1>Not found</h1>
          </main>
        )}
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
