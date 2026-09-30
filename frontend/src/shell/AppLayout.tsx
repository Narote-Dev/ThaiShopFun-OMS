import type { ReactNode } from 'react'
import type { Me } from '../auth/AuthContext'
import GraceBanner from './GraceBanner'

type Props = {
  me: Me
  readOnlyNotice: string | null
  onLogout: () => void
  children: ReactNode
}

export default function AppLayout({ me, readOnlyNotice, onLogout, children }: Props) {
  const grace = me.entitlement.status === 'GRACE'
  return (
    <>
      <header className="shell">
        <div>
          <p className="eyebrow">ThaiShopFun OMS</p>
          <strong>{me.tenant.name}</strong>
        </div>
        <div className="who">
          <span>{me.role}</span>
          <span className="badge">
            {me.tenant.membership_tier} · {me.entitlement.status}
          </span>
          <button type="button" onClick={onLogout}>
            Log out
          </button>
        </div>
        <nav>
          <a href="#/">Dashboard</a>
          <a href="#/catalog/skus">SKUs</a>
          <a href="#/catalog/products">Products</a>
          <a href="#/catalog/import">Import</a>
          <a href="#/warehouses">Warehouses</a>
          <a href="#/admin/outbox">Dead outbox</a>
        </nav>
      </header>
      {grace ? <GraceBanner expiresAt={me.entitlement.expires_at} /> : null}
      {readOnlyNotice ? <p role="alert">{readOnlyNotice}</p> : null}
      {children}
    </>
  )
}
