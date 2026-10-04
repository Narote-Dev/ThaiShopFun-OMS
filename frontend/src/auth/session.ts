import {
  InMemoryWebStorage,
  Log,
  UserManager,
  WebStorageStateStore,
  type User,
} from 'oidc-client-ts'
import { oidcConfig } from './config'
import { assertIdToken, displayNameFromIdToken } from './idToken'
import { safeReturn, stripAuthQuery } from '../routing/hash'
import { singleFlight } from './singleFlight'
import { sweepBrowserStorage } from './storage'

Log.setLevel(Log.NONE)

const settings = oidcConfig()

// Tokens live in this store. PKCE verifier/state uses sessionStorage only until the callback removes it.
const memoryStore = new InMemoryWebStorage()

const userManager = new UserManager({
  authority: settings.authority,
  client_id: settings.clientId,
  redirect_uri: settings.redirectUri,
  response_type: 'code',
  scope: settings.scope,
  userStore: new WebStorageStateStore({ store: memoryStore }),
  stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
  automaticSilentRenew: false,
  monitorSession: false,
  loadUserInfo: false,
  fetchRequestCredentials: 'omit',
})

type Listener = {
  loginRequired: () => void
}

let listener: Listener = { loginRequired: () => undefined }
let accessToken: string | null = null
let renewTimer: number | null = null
// Explicit logout and a failed callback stay on the sign-in screen. A reload starts fresh.
let manualSignIn = false
let redirecting = false

export type BootResult =
  | { kind: 'signed-in'; returnTo: string }
  | { kind: 'anonymous'; error: string | null }
  | { kind: 'redirecting' }

let bootPromise: Promise<BootResult> | null = null

export function setSessionListener(next: Listener): void {
  listener = next
}

export function getAccessToken(): string | null {
  return accessToken
}

export async function getSessionDisplayName(): Promise<string | null> {
  const user = await userManager.getUser()
  return displayNameFromIdToken(user?.id_token)
}

export function bootSession(): Promise<BootResult> {
  if (!bootPromise) bootPromise = bootOnce()
  return bootPromise
}

async function bootOnce(): Promise<BootResult> {
  // Step 1: Drop abandoned PKCE records. A callback state from this redirect is newer than the cutoff.
  await userManager.clearStaleState()
  // Step 2: A full reload has an empty memory store. state plus code or error is the callback.
  const params = new URLSearchParams(window.location.search)
  if (params.has('state') && (params.has('code') || params.has('error'))) {
    try {
      const user = await userManager.signinRedirectCallback()
      const returnTo = safeReturn(typeof user.state === 'string' ? user.state : '#/')
      // Step 3: Keep the access token. Reject an id_token that is not for this client.
      adopt(user)
      stripAuthQuery(returnTo)
      sweepBrowserStorage()
      return { kind: 'signed-in', returnTo }
    } catch (err) {
      // Step 4: The library removed the stored state. Show the provider error and do not redirect again.
      manualSignIn = true
      await clearMemory()
      stripAuthQuery('#/')
      sweepBrowserStorage()
      return {
        kind: 'anonymous',
        error: err instanceof Error ? err.message : 'Sign-in failed',
      }
    }
  }
  const existing = await userManager.getUser()
  if (existing?.access_token) {
    adopt(existing)
    sweepBrowserStorage()
    return { kind: 'signed-in', returnTo: safeReturn(window.location.hash || '#/') }
  }
  if (manualSignIn) {
    return { kind: 'anonymous', error: null }
  }
  // Step 5: No session and no callback error. Go through /authorize with the current hash.
  try {
    await startLogin(safeReturn(window.location.hash || '#/'))
    return { kind: 'redirecting' }
  } catch (err) {
    manualSignIn = true
    return {
      kind: 'anonymous',
      error: err instanceof Error ? err.message : 'Sign-in failed',
    }
  }
}

export async function startLogin(returnTo: string): Promise<void> {
  if (redirecting) return
  redirecting = true
  manualSignIn = false
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  const nonce = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  // Step 1: state and nonce are checked on the callback. The return hash is not a token.
  try {
    await userManager.signinRedirect({
      nonce,
      state: safeReturn(returnTo),
    })
  } catch (err) {
    redirecting = false
    throw err
  }
}

/** One refresh grant at a time. The mock rotates refresh tokens, so a second in-flight grant would be rejected. */
export const refreshAccessToken = singleFlight(async (): Promise<string> => {
  // Step 1: signinSilent uses the refresh_token grant when the user store has one. No iframe.
  try {
    const renewed = await userManager.signinSilent()
    if (!renewed?.access_token) {
      throw new Error('refresh failed')
    }
    adopt(renewed)
    sweepBrowserStorage()
    return renewed.access_token
  } catch (err) {
    await clearMemory()
    // Step 2: A failed refresh goes back through /authorize. Logout keeps the manual screen.
    if (manualSignIn) {
      listener.loginRequired()
    } else {
      void startLogin(safeReturn(window.location.hash || '#/')).catch(() => {
        listener.loginRequired()
      })
    }
    throw err
  }
})

/** API 401 after a failed refresh. Logout and a callback error do not redirect. */
export function resumeLogin(): void {
  if (manualSignIn) {
    listener.loginRequired()
    return
  }
  void startLogin(safeReturn(window.location.hash || '#/')).catch(() => {
    listener.loginRequired()
  })
}

export async function logout(): Promise<void> {
  // Step 1: Stay on the sign-in screen. The next full reload is a new module and authorizes again.
  manualSignIn = true
  redirecting = false
  cancelRenew()
  // Step 2: The mock discovery document has no revoke or end_session endpoint. Call them only when advertised.
  try {
    const metadata = await userManager.metadataService.getMetadata()
    if (metadata.revocation_endpoint) {
      await userManager.revokeTokens()
    }
    if (metadata.end_session_endpoint) {
      await userManager.signoutRedirect()
      return
    }
  } catch {
    // Local sign-out still proceeds when discovery is unreachable.
  }
  await clearMemory()
  sweepBrowserStorage()
}

function adopt(user: User): void {
  if (!user.access_token) {
    throw new Error('token response has no access token')
  }
  if (user.id_token && user.access_token === user.id_token) {
    throw new Error('refusing to use the id_token as the access token')
  }
  if (user.id_token) {
    assertIdToken(user.id_token, settings.authority, settings.clientId)
  }
  accessToken = user.access_token
  scheduleRenew(user.expires_in)
}

function scheduleRenew(expiresIn: number | undefined): void {
  cancelRenew()
  const seconds = typeof expiresIn === 'number' ? expiresIn : 600
  const delayMs = Math.max((seconds - 60) * 1000, 1000)
  // Step 1: Refresh before the 10 minute access token expires. A 401 still refreshes once.
  renewTimer = window.setTimeout(() => {
    void refreshAccessToken().catch(() => undefined)
  }, delayMs)
}

function cancelRenew(): void {
  if (renewTimer !== null) {
    window.clearTimeout(renewTimer)
    renewTimer = null
  }
}

async function clearMemory(): Promise<void> {
  cancelRenew()
  accessToken = null
  await userManager.removeUser()
}
