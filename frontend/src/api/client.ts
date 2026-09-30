import { getAccessToken, refreshAccessToken } from '../auth/session'

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly traceId: string | null

  constructor(status: number, code: string, message: string, traceId: string | null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.traceId = traceId
  }
}

export type PaywallCode = 'ENTITLEMENT_INACTIVE' | 'MEMBERSHIP_REVOKED'

type Handlers = {
  onPaywall: (code: PaywallCode) => void
  onReadOnly: () => void
  onSettingUp: (active: boolean) => void
  onLoginRequired: () => void
}

const noopHandlers: Handlers = {
  onPaywall: () => undefined,
  onReadOnly: () => undefined,
  onSettingUp: () => undefined,
  onLoginRequired: () => undefined,
}

type Deps = Handlers & {
  getAccessToken: () => string | null
  refreshAccessToken: () => Promise<string>
  fetchImpl: typeof fetch
  sleep: (ms: number) => Promise<void>
}

// Step 1: window.fetch throws Illegal invocation when it is detached from the window.
function browserFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  return fetch(input, init)
}

let deps: Deps = {
  ...noopHandlers,
  getAccessToken,
  refreshAccessToken,
  fetchImpl: browserFetch,
  sleep: (ms) => new Promise((resolve) => window.setTimeout(resolve, ms)),
}

const MAX_SETUP_ATTEMPTS = 3

export function configureApi(next: Partial<Deps>): void {
  deps = { ...deps, ...next }
}

export function resetApiForTests(): void {
  deps = {
    ...noopHandlers,
    getAccessToken,
    refreshAccessToken,
    fetchImpl: browserFetch,
    sleep: (ms) => new Promise((resolve) => window.setTimeout(resolve, ms)),
  }
}

type Attempt = {
  refreshed: boolean
  setupAttempts: number
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  return request<T>(path, init, { refreshed: false, setupAttempts: 0 })
}

async function request<T>(path: string, init: RequestInit, attempt: Attempt): Promise<T> {
  // Step 1: Bearer is the access token only. The id_token is never attached.
  const token = deps.getAccessToken()
  if (!token) {
    deps.onLoginRequired()
    throw new ApiError(401, 'UNAUTHORIZED', 'Sign in required', null)
  }
  const headers = new Headers(init.headers)
  headers.set('Authorization', `Bearer ${token}`)
  headers.set('Accept', 'application/json')
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const response = await deps.fetchImpl(path, { ...init, headers })
  if (response.ok) {
    deps.onSettingUp(false)
    if (response.status === 204) return undefined as T
    return (await response.json()) as T
  }
  const error = await readError(response)
  // Step 2: One refresh, then one retry. A second 401 ends the session.
  if (response.status === 401 && !attempt.refreshed && shouldRefresh(error)) {
    try {
      await deps.refreshAccessToken()
    } catch {
      deps.onLoginRequired()
      throw error
    }
    return request<T>(path, init, { ...attempt, refreshed: true })
  }
  if (response.status === 401) {
    deps.onLoginRequired()
    throw error
  }
  // Step 3: Inactive or revoked membership replaces the app with the paywall.
  if (response.status === 403 && isPaywall(error.code)) {
    deps.onPaywall(error.code)
    throw error
  }
  if (response.status === 403 && error.code === 'ENTITLEMENT_GRACE') {
    deps.onReadOnly()
    throw error
  }
  // Step 4: Unknown shop. Honor Retry-After and stop after a few tries.
  if (response.status === 503 && error.code === 'TENANT_NOT_READY' && attempt.setupAttempts + 1 < MAX_SETUP_ATTEMPTS) {
    deps.onSettingUp(true)
    await deps.sleep(retryAfterMs(response.headers.get('Retry-After')))
    return request<T>(path, init, { ...attempt, setupAttempts: attempt.setupAttempts + 1 })
  }
  if (response.status === 503 && error.code === 'TENANT_NOT_READY') {
    deps.onSettingUp(true)
  }
  throw error
}

function shouldRefresh(error: ApiError): boolean {
  return error.code === 'ENTITLEMENT_STALE' || error.message === 'Invalid or expired token'
}

function isPaywall(code: string): code is PaywallCode {
  return code === 'ENTITLEMENT_INACTIVE' || code === 'MEMBERSHIP_REVOKED'
}

async function readError(response: Response): Promise<ApiError> {
  let code = 'UNKNOWN'
  let message = response.statusText || 'Request failed'
  let traceId: string | null = null
  try {
    const body = (await response.json()) as { error?: unknown; message?: unknown; trace_id?: unknown }
    if (typeof body.error === 'string') code = body.error
    if (typeof body.message === 'string') message = body.message
    if (typeof body.trace_id === 'string') traceId = body.trace_id
  } catch {
    // Non-JSON bodies still become a typed error.
  }
  return new ApiError(response.status, code, message, traceId)
}

function retryAfterMs(header: string | null): number {
  if (!header) return 1000
  const seconds = Number(header)
  if (Number.isFinite(seconds)) return Math.min(Math.max(seconds, 0), 60) * 1000
  const when = Date.parse(header)
  if (Number.isFinite(when)) return Math.min(Math.max(when - Date.now(), 0), 60_000)
  return 1000
}
