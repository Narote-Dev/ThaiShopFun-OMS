import { useEffect, useState, type ReactNode } from 'react'
import { apiRequest, ApiError, configureApi } from '../api/client'
import { safeReturn } from '../routing/hash'
import { AuthContext, type Gate, type Me } from './AuthContext'
import { bootSession, getSessionDisplayName, logout, resumeLogin, setSessionListener, startLogin } from './session'

const READ_ONLY = 'This shop is read-only until membership is renewed.'
const SETUP_RETRYING = 'Your shop is being set up. Retrying…'

export default function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<'booting' | 'anonymous' | 'signed-in'>('booting')
  const [me, setMe] = useState<Me | null>(null)
  const [gate, setGate] = useState<Gate>('loading')
  const [setupMessage, setSetupMessage] = useState<string | null>(null)
  const [setupFailed, setSetupFailed] = useState(false)
  const [readOnlyNotice, setReadOnlyNotice] = useState<string | null>(null)
  const [signInError, setSignInError] = useState<string | null>(null)
  const [profileError, setProfileError] = useState<string | null>(null)
  const [userDisplayName, setUserDisplayName] = useState<string | null>(null)

  useEffect(() => {
    setSessionListener({
      loginRequired: () => {
        setMe(null)
        setStatus('anonymous')
      },
    })
    configureApi({
      onPaywall: () => {
        setMe(null)
        setGate('paywall')
        setSetupMessage(null)
        setSetupFailed(false)
      },
      onReadOnly: () => setReadOnlyNotice(READ_ONLY),
      onSettingUp: (active) => {
        if (!active) return
        setGate('setting-up')
        setSetupFailed(false)
        setSetupMessage(SETUP_RETRYING)
      },
      onSetupFailed: (message) => {
        setMe(null)
        setGate('setting-up')
        setSetupFailed(true)
        setSetupMessage(message)
      },
      onLoginRequired: () => {
        resumeLogin()
      },
    })
  }, [])

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const result = await bootSession()
      if (cancelled) return
      if (result.kind === 'redirecting') return
      if (result.kind === 'anonymous') {
        setSignInError(result.error)
        setStatus('anonymous')
        return
      }
      setStatus('signed-in')
      await loadProfile(() => cancelled)
    })()
    return () => {
      cancelled = true
    }
  }, [])

  async function loadProfile(isCancelled: () => boolean = () => false): Promise<void> {
    try {
      const profile = await apiRequest<Me>('/api/v1/me')
      if (isCancelled()) return
      setMe(profile)
      const name = await getSessionDisplayName()
      setUserDisplayName(name)
      setGate('ready')
      setSetupFailed(false)
      setSetupMessage(null)
      setProfileError(null)
    } catch (err) {
      if (isCancelled()) return
      if (err instanceof ApiError && err.code === 'TENANT_NOT_READY') {
        // Step 1: Bounded retries already stopped. One message and a Retry button, not "Retrying…".
        setMe(null)
        setGate('setting-up')
        setSetupFailed(true)
        setSetupMessage(err.message)
        return
      }
      if (err instanceof ApiError && (err.code === 'ENTITLEMENT_INACTIVE' || err.code === 'MEMBERSHIP_REVOKED')) {
        setMe(null)
        setGate('paywall')
        setSetupMessage(null)
        return
      }
      // Step 2: A failed profile load must not sit on the loading gate.
      setProfileError(err instanceof Error ? err.message : 'Could not load the shop')
    }
  }

  function signIn() {
    setSignInError(null)
    setStatus('booting')
    void startLogin(safeReturn(window.location.hash || '#/')).catch((err: unknown) => {
      setSignInError(err instanceof Error ? err.message : 'Sign-in failed')
      setStatus('anonymous')
    })
  }

  function signOut() {
    void logout().finally(() => {
      setMe(null)
      setGate('loading')
      setReadOnlyNotice(null)
      setProfileError(null)
      setUserDisplayName(null)
      setSetupMessage(null)
      setSetupFailed(false)
      setSignInError(null)
      setStatus('anonymous')
      if (window.location.hash !== '#/') {
        window.location.hash = '#/'
      }
    })
  }

  function retrySetup() {
    setSetupFailed(false)
    setSetupMessage(SETUP_RETRYING)
    setGate('setting-up')
    void loadProfile()
  }

  return (
    <AuthContext.Provider
      value={{
        status,
        me,
        gate,
        setupMessage,
        setupFailed,
        readOnlyNotice,
        signInError,
        profileError,
        userDisplayName,
        signIn,
        signOut,
        retrySetup,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}
