import { describe, expect, it } from 'vitest'
import { assertIdToken } from './idToken'

function token(claims: unknown): string {
  const body = btoa(JSON.stringify(claims)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `aaa.${body}.sig`
}

describe('assertIdToken', () => {
  it('accepts the public client audience and rejects the API audience', () => {
    expect(() =>
      assertIdToken(
        token({ iss: 'http://localhost:8090/tsf-idp', aud: 'oms-web' }),
        'http://localhost:8090/tsf-idp',
        'oms-web',
      ),
    ).not.toThrow()
    expect(() =>
      assertIdToken(
        token({ iss: 'http://localhost:8090/tsf-idp', aud: 'oms' }),
        'http://localhost:8090/tsf-idp',
        'oms-web',
      ),
    ).toThrow(/audience/)
  })
})
