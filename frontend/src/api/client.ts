import { getAccessToken, refreshAccessToken } from '../auth/session'

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly traceId: string | null
  // Change: extra body data, e.g. the per-row `errors` list of 422 IMPORT_INVALID.
  readonly details: unknown

  constructor(status: number, code: string, message: string, traceId: string | null, details: unknown = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.traceId = traceId
    this.details = details
  }
}

export type PaywallCode = 'ENTITLEMENT_INACTIVE' | 'MEMBERSHIP_REVOKED'

type Handlers = {
  onPaywall: (code: PaywallCode) => void
  onReadOnly: () => void
  onSettingUp: (active: boolean) => void
  onSetupFailed: (message: string) => void
  onLoginRequired: () => void
}

const noopHandlers: Handlers = {
  onPaywall: () => undefined,
  onReadOnly: () => undefined,
  onSettingUp: () => undefined,
  onSetupFailed: () => undefined,
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
  const sentToken = token
  const headers = new Headers(init.headers)
  headers.set('Authorization', `Bearer ${sentToken}`)
  headers.set('Accept', 'application/json')
  // Change: FormData (CSV import) needs the browser to set the multipart boundary itself.
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const response = await deps.fetchImpl(path, { ...init, headers })
  if (response.ok) {
    deps.onSettingUp(false)
    if (response.status === 204) return undefined as T
    return (await response.json()) as T
  }
  const error = await readError(response)
  // Step 2: Another request may already have rotated the access token. Reuse it. Do not spend the refresh token twice.
  if (response.status === 401 && shouldRefresh(error)) {
    const latest = deps.getAccessToken()
    if (latest && latest !== sentToken) {
      return request<T>(path, init, attempt)
    }
    if (!attempt.refreshed) {
      try {
        await deps.refreshAccessToken()
      } catch {
        deps.onLoginRequired()
        throw error
      }
      return request<T>(path, init, { ...attempt, refreshed: true })
    }
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
  // Step 4: Unknown shop. Honor Retry-After, then stop so the screen can offer Retry.
  if (response.status === 503 && error.code === 'TENANT_NOT_READY' && attempt.setupAttempts + 1 < MAX_SETUP_ATTEMPTS) {
    deps.onSettingUp(true)
    await deps.sleep(retryAfterMs(response.headers.get('Retry-After')))
    return request<T>(path, init, { ...attempt, setupAttempts: attempt.setupAttempts + 1 })
  }
  if (response.status === 503 && error.code === 'TENANT_NOT_READY') {
    deps.onSetupFailed(error.message)
  }
  throw error
}

function shouldRefresh(error: ApiError): boolean {
  return error.code === 'UNAUTHORIZED' || error.code === 'ENTITLEMENT_STALE'
}

function isPaywall(code: string): code is PaywallCode {
  return code === 'ENTITLEMENT_INACTIVE' || code === 'MEMBERSHIP_REVOKED'
}

async function readError(response: Response): Promise<ApiError> {
  let code = 'UNKNOWN'
  let message = response.statusText || 'Request failed'
  let traceId: string | null = null
  let details: unknown = null
  try {
    const body = (await response.json()) as {
      error?: unknown
      message?: unknown
      trace_id?: unknown
      errors?: unknown
    }
    if (typeof body.error === 'string') code = body.error
    if (typeof body.message === 'string') message = body.message
    if (typeof body.trace_id === 'string') traceId = body.trace_id
    if (Array.isArray(body.errors)) details = body.errors
  } catch {
    // Non-JSON bodies still become a typed error.
  }
  return new ApiError(response.status, code, message, traceId, details)
}

function retryAfterMs(header: string | null): number {
  if (!header) return 1000
  const seconds = Number(header)
  if (Number.isFinite(seconds)) return Math.min(Math.max(seconds, 0), 60) * 1000
  const when = Date.parse(header)
  if (Number.isFinite(when)) return Math.min(Math.max(when - Date.now(), 0), 60_000)
  return 1000
}
