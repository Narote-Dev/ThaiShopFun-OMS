const TOKEN_KEY = 'oms-staging-access-token'
const EXPIRES_KEY = 'oms-staging-expires-at'

export type StagingBootResult =
  | { kind: 'signed-in'; returnTo: string }
  | { kind: 'anonymous'; error: string | null }

type Listener = { loginRequired: () => void }

let listener: Listener = { loginRequired: () => undefined }
let accessToken: string | null = null

export function stagingApiBase(): string {
  const raw = import.meta.env.VITE_OMS_API_BASE_URL?.trim() ?? ''
  return raw.replace(/\/$/, '')
}

export function stagingIdpPath(): string {
  return (import.meta.env.VITE_OMS_STAGING_IDP_PATH ?? '/staging-idp').replace(/\/$/, '')
}

export function stagingLoginEnabled(): boolean {
  return import.meta.env.VITE_OMS_STAGING_LOGIN === 'true'
}

export function setStagingSessionListener(next: Listener): void {
  listener = next
}

export function getAccessToken(): string | null {
  if (accessToken) return accessToken
  const stored = sessionStorage.getItem(TOKEN_KEY)
  const expires = Number(sessionStorage.getItem(EXPIRES_KEY) ?? '0')
  if (!stored || !Number.isFinite(expires) || expires <= Date.now()) {
    sessionStorage.removeItem(TOKEN_KEY)
    sessionStorage.removeItem(EXPIRES_KEY)
    return null
  }
  accessToken = stored
  return accessToken
}

export async function stagingBootSession(): Promise<StagingBootResult> {
  const token = getAccessToken()
  if (token) {
    return { kind: 'signed-in', returnTo: window.location.hash || '#/' }
  }
  return { kind: 'anonymous', error: null }
}

export async function stagingLogin(loginId: string, password: string): Promise<void> {
  const base = stagingApiBase()
  const path = `${stagingIdpPath()}/login`
  const url = `${base}${path}`
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ login_id: loginId, password }),
  })
  if (!response.ok) {
    let message = 'เข้าสู่ระบบไม่สำเร็จ'
    try {
      const body = (await response.json()) as { message?: string }
      if (typeof body.message === 'string' && body.message.trim()) {
        message = body.message
      }
    } catch {
      // ignore
    }
    throw new Error(message)
  }
  const body = (await response.json()) as { access_token?: string; expires_in?: number }
  if (!body.access_token) {
    throw new Error('เข้าสู่ระบบไม่สำเร็จ')
  }
  const expiresIn = typeof body.expires_in === 'number' ? body.expires_in : 3600
  accessToken = body.access_token
  sessionStorage.setItem(TOKEN_KEY, body.access_token)
  sessionStorage.setItem(EXPIRES_KEY, String(Date.now() + expiresIn * 1000))
}

export async function fetchStagingDemoAccounts(): Promise<
  { login_id: string; shop_name: string; shop_role: string }[]
> {
  const base = stagingApiBase()
  const url = `${base}${stagingIdpPath()}/demo-accounts`
  const response = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!response.ok) return []
  return (await response.json()) as { login_id: string; shop_name: string; shop_role: string }[]
}

export async function refreshAccessToken(): Promise<string> {
  listener.loginRequired()
  throw new Error('กรุณาเข้าสู่ระบบอีกครั้ง')
}

export async function stagingLogout(): Promise<void> {
  accessToken = null
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(EXPIRES_KEY)
}

export async function getSessionDisplayName(): Promise<string | null> {
  return null
}
