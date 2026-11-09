interface ImportMetaEnv {
  readonly VITE_OIDC_AUTHORITY?: string
  readonly VITE_OIDC_CLIENT_ID?: string
  readonly VITE_OIDC_REDIRECT_URI?: string
  readonly VITE_OMS_API_BASE_URL?: string
  readonly VITE_OMS_STAGING_LOGIN?: string
  readonly VITE_OMS_STAGING_IDP_PATH?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
