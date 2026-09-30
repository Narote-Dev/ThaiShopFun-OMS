import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiRequest, ApiError, configureApi, resetApiForTests } from './client'
import { singleFlight } from '../auth/singleFlight'

function json(status: number, body: unknown, headers?: HeadersInit): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

afterEach(() => {
  resetApiForTests()
  vi.restoreAllMocks()
})

describe('apiRequest', () => {
  it('sends the access token and not another bearer', async () => {
    const fetchImpl = vi.fn(async () => json(200, { ok: true }))
    configureApi({
      getAccessToken: () => 'access-token',
      fetchImpl,
    })
    await apiRequest('/api/v1/me')
    const init = (fetchImpl.mock.calls as unknown as [RequestInfo | URL, RequestInit?][])[0][1]
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer access-token')
  })

  it('refreshes once and retries a stale entitlement', async () => {
    let token = 'old'
    const fetchImpl = vi.fn(async () => {
      if (token === 'old') {
        return json(401, { error: 'ENTITLEMENT_STALE', message: 'Invalid or expired token' })
      }
      return json(200, { tenant: { name: 'Active Shop' } })
    })
    const refreshAccessToken = vi.fn(async () => {
      token = 'new'
      return 'new'
    })
    configureApi({
      getAccessToken: () => token,
      refreshAccessToken,
      fetchImpl,
    })
    const body = await apiRequest<{ tenant: { name: string } }>('/api/v1/me')
    expect(body.tenant.name).toBe('Active Shop')
    expect(refreshAccessToken).toHaveBeenCalledTimes(1)
    expect(fetchImpl).toHaveBeenCalledTimes(2)
    const retry = (fetchImpl.mock.calls as unknown as [RequestInfo | URL, RequestInit?][])[1][1]
    expect(new Headers(retry?.headers).get('Authorization')).toBe('Bearer new')
  })

  it('refreshes once for an expired token and logs in if the retry is still 401', async () => {
    const login = vi.fn()
    const fetchImpl = vi.fn(async () =>
      json(401, { error: 'UNAUTHORIZED', message: 'Invalid or expired token' }),
    )
    configureApi({
      getAccessToken: () => 'expired',
      refreshAccessToken: async () => 'still-bad',
      onLoginRequired: login,
      fetchImpl,
    })
    await expect(apiRequest('/api/v1/me')).rejects.toBeInstanceOf(ApiError)
    expect(fetchImpl).toHaveBeenCalledTimes(2)
    expect(login).toHaveBeenCalledTimes(1)
  })

  it('shares one refresh across overlapping 401s', async () => {
    let token = 'old'
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    let calls = 0
    const refreshAccessToken = singleFlight(async () => {
      calls += 1
      await gate
      token = 'new'
      return 'new'
    })
    const fetchImpl = vi.fn(async () => {
      if (token === 'old') {
        return json(401, { error: 'ENTITLEMENT_STALE', message: 'Invalid or expired token' })
      }
      return json(200, { ok: true })
    })
    configureApi({
      getAccessToken: () => token,
      refreshAccessToken,
      fetchImpl,
    })
    const pending = Promise.all([apiRequest('/api/v1/me'), apiRequest('/api/v1/outbox')])
    await vi.waitFor(() => expect(calls).toBe(1))
    release()
    await pending
    expect(calls).toBe(1)
  })

  it('maps inactive and revoked membership to the paywall', async () => {
    const paywall = vi.fn()
    const fetchImpl = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return json(403, { error: 'MEMBERSHIP_REVOKED', message: 'Membership revoked' })
      }
      return json(403, { error: 'ENTITLEMENT_INACTIVE', message: 'Membership expired' })
    })
    configureApi({
      getAccessToken: () => 'token',
      onPaywall: paywall,
      fetchImpl,
    })
    await expect(apiRequest('/api/v1/me')).rejects.toMatchObject({ code: 'ENTITLEMENT_INACTIVE' })
    await expect(apiRequest('/api/v1/outbox/1/retry', { method: 'POST' })).rejects.toMatchObject({
      code: 'MEMBERSHIP_REVOKED',
    })
    expect(paywall).toHaveBeenCalledWith('ENTITLEMENT_INACTIVE')
    expect(paywall).toHaveBeenCalledWith('MEMBERSHIP_REVOKED')
  })

  it('maps a grace write to the read-only notice', async () => {
    const readOnly = vi.fn()
    configureApi({
      getAccessToken: () => 'token',
      onReadOnly: readOnly,
      fetchImpl: async () =>
        json(403, { error: 'ENTITLEMENT_GRACE', message: 'Membership is in a grace period' }),
    })
    await expect(apiRequest('/api/v1/outbox/1/retry', { method: 'POST' })).rejects.toMatchObject({
      code: 'ENTITLEMENT_GRACE',
    })
    expect(readOnly).toHaveBeenCalledTimes(1)
  })

  it('retries with a token another request already rotated', async () => {
    let token = 'old'
    const refreshAccessToken = vi.fn(async () => 'should-not-run')
    const fetchImpl = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const authorization = new Headers(init?.headers).get('Authorization')
      if (authorization === 'Bearer old') {
        token = 'new'
        return json(401, { error: 'UNAUTHORIZED', message: 'Invalid or expired token' })
      }
      return json(200, { ok: true })
    })
    configureApi({
      getAccessToken: () => token,
      refreshAccessToken,
      fetchImpl,
    })
    await expect(apiRequest('/api/v1/me')).resolves.toEqual({ ok: true })
    expect(refreshAccessToken).not.toHaveBeenCalled()
    expect(fetchImpl).toHaveBeenCalledTimes(2)
  })

  it('does not refresh a 401 whose code is neither UNAUTHORIZED nor ENTITLEMENT_STALE', async () => {
    const refreshAccessToken = vi.fn(async () => 'new')
    const login = vi.fn()
    configureApi({
      getAccessToken: () => 'token',
      refreshAccessToken,
      onLoginRequired: login,
      fetchImpl: async () => json(401, { error: 'OTHER', message: 'Invalid or expired token' }),
    })
    await expect(apiRequest('/api/v1/me')).rejects.toMatchObject({ code: 'OTHER' })
    expect(refreshAccessToken).not.toHaveBeenCalled()
    expect(login).toHaveBeenCalledTimes(1)
  })

  it('retries TENANT_NOT_READY a bounded number of times', async () => {
    const sleeps: number[] = []
    const settingUp = vi.fn()
    let n = 0
    const fetchImpl = vi.fn(async () => {
      n += 1
      if (n < 3) {
        return json(503, { error: 'TENANT_NOT_READY', message: 'Shop is not registered yet' }, {
          'Retry-After': '2',
        })
      }
      return json(200, { tenant: { name: 'Active Shop' } })
    })
    configureApi({
      getAccessToken: () => 'token',
      onSettingUp: settingUp,
      fetchImpl,
      sleep: async (ms) => {
        sleeps.push(ms)
      },
    })
    const body = await apiRequest<{ tenant: { name: string } }>('/api/v1/me')
    expect(body.tenant.name).toBe('Active Shop')
    expect(fetchImpl).toHaveBeenCalledTimes(3)
    expect(sleeps).toEqual([2000, 2000])
    expect(settingUp).toHaveBeenCalledWith(true)
  })

  it('stops retrying TENANT_NOT_READY after the bound', async () => {
    const setupFailed = vi.fn()
    const fetchImpl = vi.fn(async () =>
      json(503, { error: 'TENANT_NOT_READY', message: 'Shop is not registered yet' }, {
        'Retry-After': '1',
      }),
    )
    configureApi({
      getAccessToken: () => 'token',
      onSetupFailed: setupFailed,
      fetchImpl,
      sleep: async () => undefined,
    })
    await expect(apiRequest('/api/v1/me')).rejects.toMatchObject({ code: 'TENANT_NOT_READY' })
    expect(fetchImpl).toHaveBeenCalledTimes(3)
    expect(setupFailed).toHaveBeenCalledTimes(1)
    expect(setupFailed).toHaveBeenCalledWith('Shop is not registered yet')
  })

  it('calls fetch without using it as a method', async () => {
    const real = globalThis.fetch
    globalThis.fetch = function (this: unknown) {
      if (this != null && this !== globalThis) {
        throw new TypeError("Failed to execute 'fetch' on 'Window': Illegal invocation")
      }
      return Promise.resolve(json(200, { ok: true }))
    } as typeof fetch
    try {
      resetApiForTests()
      configureApi({ getAccessToken: () => 'token' })
      await expect(apiRequest('/api/v1/me')).resolves.toEqual({ ok: true })
    } finally {
      globalThis.fetch = real
    }
  })

  it('surfaces other errors as ApiError', async () => {
    configureApi({
      getAccessToken: () => 'token',
      fetchImpl: async () => json(500, { error: 'INTERNAL_ERROR', message: 'Unexpected error', trace_id: 'abc' }),
    })
    const error = await apiRequest('/api/v1/me').catch((err: unknown) => err)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 500, code: 'INTERNAL_ERROR', traceId: 'abc' })
  })
})
