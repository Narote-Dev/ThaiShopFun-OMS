import type { ReactNode } from 'react'
import type { Me } from '../auth/AuthContext'
import AppShell from '../ui/AppShell'
import GraceBanner from './GraceBanner'

type Props = {
  me: Me
  route: string
  userDisplayName: string | null
  readOnlyNotice: string | null
  onLogout: () => void
  children: ReactNode
}

export default function AppLayout({ me, route, userDisplayName, readOnlyNotice, onLogout, children }: Props) {
  const grace = me.entitlement.status === 'GRACE'
  return (
    <AppShell me={me} route={route} userDisplayName={userDisplayName} onLogout={onLogout}>
      {grace ? <GraceBanner expiresAt={me.entitlement.expires_at} /> : null}
      {readOnlyNotice ? (
        <p role="alert" className="border-b border-amber-200 bg-amber-50 px-6 py-2 text-[13px] text-amber-950">
          {readOnlyNotice}
        </p>
      ) : null}
      {children}
    </AppShell>
  )
}
