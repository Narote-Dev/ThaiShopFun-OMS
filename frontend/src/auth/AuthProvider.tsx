import { useEffect, useState, type ReactNode } from 'react'
import { apiRequest, ApiError, configureApi, type PaywallCode } from '../api/client'
import { safeReturn } from '../routing/hash'
import { AuthContext, type Gate, type Me } from './AuthContext'
import { bootSession, logout, setSessionListener, startLogin } from './session'

const READ_ONLY = 'This shop is read-only until membership is renewed.'

export default function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<'booting' | 'anonymous' | 'signed-in'>('booting')
  const [me, setMe] = useState<Me | null>(null)
  const [gate, setGate] = useState<Gate>('loading')
  const [setupMessage, setSetupMessage] = useState<string | null>(null)
  const [readOnlyNotice, setReadOnlyNotice] = useState<string | null>(null)
  const [signInError, setSignInError] = useState<string | null>(null)
  const [profileError, setProfileError] = useState<string | null>(null)

  useEffect(() => {
    setSessionListener({
      loginRequired: () => {
        setMe(null)
        setStatus('anonymous')
      },
    })
    configureApi({
      onPaywall: (code: PaywallCode) => {
        setMe(null)
        setGate('paywall')
        setSetupMessage(code)
      },
      onReadOnly: () => setReadOnlyNotice(READ_ONLY),
      onSettingUp: (active) => {
        if (active) setGate('setting-up')
      },
      onLoginRequired: () => {
        setMe(null)
        setStatus('anonymous')
      },
    })
  }, [])

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const result = await bootSession()
      if (cancelled) return
      if (result.kind === 'anonymous') {
        setSignInError(result.error)
        setStatus('anonymous')
        return
      }
      setStatus('signed-in')
      try {
        const profile = await apiRequest<Me>('/api/v1/me')
        if (cancelled) return
        setMe(profile)
        setGate('ready')
      } catch (err) {
        if (cancelled) return
        if (err instanceof ApiError && err.code === 'TENANT_NOT_READY') {
          setGate('setting-up')
          setSetupMessage('Your shop is being set up. Retrying…')
          return
        }
        if (err instanceof ApiError && (err.code === 'ENTITLEMENT_INACTIVE' || err.code === 'MEMBERSHIP_REVOKED')) {
          setMe(null)
          setGate('paywall')
          return
        }
        // Step 2: A failed profile load must not sit on the loading gate.
        setProfileError(err instanceof Error ? err.message : 'Could not load the shop')
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  function signIn() {
    setSignInError(null)
    void startLogin(safeReturn(window.location.hash || '#/')).catch((err: unknown) => {
      setSignInError(err instanceof Error ? err.message : 'Sign-in failed')
    })
  }

  function signOut() {
    void logout().finally(() => {
      setMe(null)
      setGate('loading')
      setReadOnlyNotice(null)
      setProfileError(null)
      setStatus('anonymous')
      if (window.location.hash !== '#/') {
        window.location.hash = '#/'
      }
    })
  }

  return (
    <AuthContext.Provider
      value={{ status, me, gate, setupMessage, readOnlyNotice, signInError, profileError, signIn, signOut }}
    >
      {children}
    </AuthContext.Provider>
  )
}
