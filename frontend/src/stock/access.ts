import type { Me } from '../auth/AuthContext'
import type { DocumentType } from './api'

export type StockAccess = {
  /** Create and edit drafts: any member, not in GRACE. */
  canEdit: boolean
  /** Void any posted document: OWNER or ADMIN, not in GRACE. */
  canVoid: boolean
  /** Post a document of this type: RECEIVE for any member, the rest OWNER or ADMIN. */
  canPost: (type: DocumentType) => boolean
}

export function stockAccess(me: Me): StockAccess {
  const grace = me.entitlement.status === 'GRACE'
  const manager = me.role === 'OWNER' || me.role === 'ADMIN'
  return {
    canEdit: !grace,
    canVoid: !grace && manager,
    canPost: (type) => !grace && (type === 'RECEIVE' || manager),
  }
}

export const READ_ONLY_ACCESS: StockAccess = { canEdit: false, canVoid: false, canPost: () => false }
