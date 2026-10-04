import { createContext } from 'react'

export type Me = {
  tenant: {
    id: string
    name: string
    tsf_shop_id: string
    membership_tier: string
  }
  role: string
  entitlement: {
    status: string
    expires_at: string | null
    ent_ver: number
  }
}

export type Gate = 'loading' | 'ready' | 'paywall' | 'setting-up'

export type AuthContextValue = {
  status: 'booting' | 'anonymous' | 'signed-in'
  me: Me | null
  gate: Gate
  setupMessage: string | null
  setupFailed: boolean
  readOnlyNotice: string | null
  signInError: string | null
  profileError: string | null
  userDisplayName: string | null
  signIn: () => void
  signOut: () => void
  retrySetup: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)
