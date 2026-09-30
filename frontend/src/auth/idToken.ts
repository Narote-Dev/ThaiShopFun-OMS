type IdClaims = {
  iss?: string
  aud?: string | string[]
  nonce?: string
}

export function decodeIdClaims(idToken: string): IdClaims {
  const parts = idToken.split('.')
  if (parts.length < 2) {
    throw new Error('id_token is not a JWT')
  }
  const segment = parts[1].replace(/-/g, '+').replace(/_/g, '/')
  const padded = segment + '='.repeat((4 - (segment.length % 4)) % 4)
  const json = atob(padded)
  return JSON.parse(json) as IdClaims
}

/** id_token aud is the public client. It is never a backend bearer. */
export function assertIdToken(idToken: string, issuer: string, clientId: string): void {
  const claims = decodeIdClaims(idToken)
  if (claims.iss !== issuer) {
    throw new Error('id_token issuer does not match the authority')
  }
  const aud = claims.aud
  const matches = aud === clientId || (Array.isArray(aud) && aud.includes(clientId))
  if (!matches) {
    throw new Error('id_token audience does not match the client')
  }
}
