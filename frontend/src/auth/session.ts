import {
  InMemoryWebStorage,
  Log,
  UserManager,
  WebStorageStateStore,
  type User,
} from 'oidc-client-ts'
import { oidcConfig } from './config'
import { assertIdToken } from './idToken'
import { safeReturn, stripAuthQuery } from '../routing/hash'
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
let refreshFlight: Promise<string> | null = null
let renewTimer: number | null = null

export type BootResult =
  | { kind: 'signed-in'; returnTo: string }
  | { kind: 'anonymous'; error: string | null }

let bootPromise: Promise<BootResult> | null = null

export function setSessionListener(next: Listener): void {
  listener = next
}

export function getAccessToken(): string | null {
  return accessToken
}

export function bootSession(): Promise<BootResult> {
  if (!bootPromise) bootPromise = bootOnce()
  return bootPromise
}

async function bootOnce(): Promise<BootResult> {
  // Step 1: A full reload has an empty memory store. A callback URL is the only resume path.
  const params = new URLSearchParams(window.location.search)
  if (params.has('code') && params.has('state')) {
    try {
      const user = await userManager.signinRedirectCallback()
      const returnTo = safeReturn(typeof user.state === 'string' ? user.state : '#/')
      // Step 2: Keep the access token. Reject an id_token that is not for this client.
      adopt(user)
      stripAuthQuery(returnTo)
      sweepBrowserStorage()
      return { kind: 'signed-in', returnTo }
    } catch (err) {
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
  return { kind: 'anonymous', error: null }
}

export async function startLogin(returnTo: string): Promise<void> {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  const nonce = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  // Step 1: state and nonce are checked on the callback. The return hash is not a token.
  await userManager.signinRedirect({
    nonce,
    state: safeReturn(returnTo),
  })
}

/** One refresh grant at a time. The mock rotates refresh tokens, so a second in-flight grant would be rejected. */
export function refreshAccessToken(): Promise<string> {
  if (!refreshFlight) {
    refreshFlight = refreshOnce().finally(() => {
      refreshFlight = null
    })
  }
  return refreshFlight
}

async function refreshOnce(): Promise<string> {
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
    throw err
  }
}

export async function logout(): Promise<void> {
  // Step 1: The mock discovery document has no revoke or end_session endpoint. Call them only when advertised.
  cancelRenew()
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
    void refreshAccessToken().catch(() => {
      listener.loginRequired()
    })
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
