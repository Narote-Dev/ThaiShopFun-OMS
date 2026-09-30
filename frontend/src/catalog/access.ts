import type { Me } from '../auth/AuthContext'

/** Catalog and warehouse writes: OWNER or ADMIN, and not while the shop is in GRACE. */
export function canWriteCatalog(me: Me): boolean {
  if (me.entitlement.status === 'GRACE') return false
  return me.role === 'OWNER' || me.role === 'ADMIN'
}
