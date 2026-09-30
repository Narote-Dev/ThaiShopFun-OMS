import { beforeEach, describe, expect, it, vi } from 'vitest'

const oidc = vi.hoisted(() => ({
  signinRedirect: vi.fn(async () => undefined),
  signinRedirectCallback: vi.fn(),
  getUser: vi.fn(async () => null),
  clearStaleState: vi.fn(async () => undefined),
  signinSilent: vi.fn(),
  removeUser: vi.fn(async () => undefined),
  revokeTokens: vi.fn(async () => undefined),
  signoutRedirect: vi.fn(async () => undefined),
  getMetadata: vi.fn(async () => ({})),
}))

vi.mock('oidc-client-ts', () => ({
  Log: { NONE: 0, setLevel: () => undefined },
  InMemoryWebStorage: class {},
  WebStorageStateStore: class {},
  UserManager: class {
    signinRedirect = oidc.signinRedirect
    signinRedirectCallback = oidc.signinRedirectCallback
    getUser = oidc.getUser
    clearStaleState = oidc.clearStaleState
    signinSilent = oidc.signinSilent
    removeUser = oidc.removeUser
    revokeTokens = oidc.revokeTokens
    signoutRedirect = oidc.signoutRedirect
    metadataService = { getMetadata: oidc.getMetadata }
  },
}))

async function loadSession() {
  vi.resetModules()
  return import('./session')
}

beforeEach(() => {
  oidc.signinRedirect.mockClear()
  oidc.signinRedirect.mockResolvedValue(undefined)
  oidc.signinRedirectCallback.mockReset()
  oidc.getUser.mockReset()
  oidc.getUser.mockResolvedValue(null)
  oidc.clearStaleState.mockClear()
  oidc.clearStaleState.mockResolvedValue(undefined)
  oidc.signinSilent.mockReset()
  oidc.removeUser.mockClear()
  oidc.removeUser.mockResolvedValue(undefined)
  oidc.getMetadata.mockReset()
  oidc.getMetadata.mockResolvedValue({})
  window.history.replaceState(null, '', '/')
})

describe('boot', () => {
  it('anonymous boot triggers signinRedirect', async () => {
    window.history.replaceState(null, '', '/#/admin/outbox')
    const session = await loadSession()
    const result = await session.bootSession()
    expect(oidc.clearStaleState).toHaveBeenCalled()
    expect(oidc.signinRedirect).toHaveBeenCalledTimes(1)
    expect(oidc.signinRedirect).toHaveBeenCalledWith(expect.objectContaining({ state: '#/admin/outbox' }))
    expect(result).toEqual({ kind: 'redirecting' })
  })

  it('after logout or a callback error it does not', async () => {
    const loggedOut = await loadSession()
    await loggedOut.logout()
    oidc.signinRedirect.mockClear()
    const afterLogout = await loggedOut.bootSession()
    expect(oidc.signinRedirect).not.toHaveBeenCalled()
    expect(afterLogout).toEqual({ kind: 'anonymous', error: null })

    oidc.signinRedirectCallback.mockRejectedValue(new Error('access_denied'))
    window.history.replaceState(null, '', '/?error=access_denied&error_description=nope&state=abc')
    const failed = await loadSession()
    const result = await failed.bootSession()
    expect(oidc.signinRedirectCallback).toHaveBeenCalled()
    expect(oidc.signinRedirect).not.toHaveBeenCalled()
    expect(result).toEqual({ kind: 'anonymous', error: 'access_denied' })
    expect(window.location.search).not.toContain('state=')
    expect(window.location.search).not.toContain('error=')
  })

  it('failed refresh triggers signinRedirect', async () => {
    oidc.getUser.mockResolvedValue({ access_token: 'old', expires_in: 600 } as never)
    oidc.signinSilent.mockRejectedValue(new Error('invalid_grant'))
    const session = await loadSession()
    await session.bootSession()
    expect(oidc.signinRedirect).not.toHaveBeenCalled()
    await expect(session.refreshAccessToken()).rejects.toThrow('invalid_grant')
    expect(oidc.signinRedirect).toHaveBeenCalledTimes(1)
  })
})
