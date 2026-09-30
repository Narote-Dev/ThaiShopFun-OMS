import { describe, expect, it } from 'vitest'
import { sweepBrowserStorage } from './storage'

describe('sweepBrowserStorage', () => {
  it('removes oidc user entries and JWT-shaped values', () => {
    const jwt = 'eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.sig'
    localStorage.setItem('oidc.user:http://localhost:8090/tsf-idp:oms-web', JSON.stringify({ access_token: jwt }))
    sessionStorage.setItem('oidc.abc', jwt)
    sessionStorage.setItem('return', '#/admin/outbox')
    sweepBrowserStorage()
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.getItem('return')).toBe('#/admin/outbox')
    expect(sessionStorage.getItem('oidc.abc')).toBeNull()
  })
})
