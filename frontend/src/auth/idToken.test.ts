import { describe, expect, it } from 'vitest'
import { assertIdToken, decodeIdClaims, userLabelFromIdToken } from './idToken'

function token(claims: unknown): string {
  const body = btoa(JSON.stringify(claims)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `aaa.${body}.sig`
}

function tokenUtf8(claims: object): string {
  const bytes = new TextEncoder().encode(JSON.stringify(claims))
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  const body = btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
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

describe('decodeIdClaims', () => {
  it('decodes UTF-8 payload (Thai name)', () => {
    const thaiName = 'นโรต ณ.'
    const jwt = tokenUtf8({ name: thaiName, sub: 'user-1' })
    expect(decodeIdClaims(jwt).name).toBe(thaiName)
    expect(userLabelFromIdToken(jwt)).toBe(thaiName)
  })

  it('falls back to email then sub when name is missing', () => {
    const jwt = token({ email: 'owner@example.com', sub: 'sub-99' })
    expect(userLabelFromIdToken(jwt)).toBe('owner@example.com')
    const subOnly = token({ sub: 'sub-only' })
    expect(userLabelFromIdToken(subOnly)).toBe('sub-only')
  })
})
