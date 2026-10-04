type IdClaims = {
  iss?: string
  aud?: string | string[]
  nonce?: string
  name?: string
  preferred_username?: string
  email?: string
  sub?: string
}

function base64UrlDecode(segment: string): string {
  const base64 = segment.replace(/-/g, '+').replace(/_/g, '/')
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4)
  const binary = atob(padded)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return new TextDecoder('utf-8').decode(bytes)
}

export function userLabelFromIdToken(idToken: string | undefined | null): string | null {
  if (!idToken) return null
  try {
    const claims = decodeIdClaims(idToken) as IdClaims
    return (
      claims.name?.trim() ||
      claims.preferred_username?.trim() ||
      claims.email?.trim() ||
      claims.sub?.trim() ||
      null
    )
  } catch {
    return null
  }
}

/** @deprecated use userLabelFromIdToken */
export function displayNameFromIdToken(idToken: string | undefined | null): string | null {
  return userLabelFromIdToken(idToken)
}

export function decodeIdClaims(idToken: string): IdClaims {
  const parts = idToken.split('.')
  if (parts.length < 2) {
    throw new Error('id_token is not a JWT')
  }
  const json = base64UrlDecode(parts[1])
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
