import type { Me } from '../../auth/AuthContext'

export type ListingsAccess = {
  canWrite: boolean
  grace: boolean
}

export function listingsAccess(me: Me): ListingsAccess {
  const manager = me.role === 'OWNER' || me.role === 'ADMIN'
  const grace = me.entitlement.status === 'GRACE'
  return { canWrite: manager && !grace, grace }
}
