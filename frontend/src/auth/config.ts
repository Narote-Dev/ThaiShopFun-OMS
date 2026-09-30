const DEFAULT_AUTHORITY = 'http://localhost:8090/tsf-idp'
const DEFAULT_CLIENT_ID = 'oms-web'

export type OidcConfig = {
  authority: string
  clientId: string
  redirectUri: string
  scope: 'openid oms'
}

export function oidcConfig(): OidcConfig {
  const authority = (import.meta.env.VITE_OIDC_AUTHORITY || DEFAULT_AUTHORITY).replace(/\/$/, '')
  const clientId = import.meta.env.VITE_OIDC_CLIENT_ID || DEFAULT_CLIENT_ID
  const configured = import.meta.env.VITE_OIDC_REDIRECT_URI
  const redirectUri = (configured || `${window.location.origin}/`).split('#')[0]
  return { authority, clientId, redirectUri, scope: 'openid oms' }
}
