const JWT = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/

function looksSecret(value: string): boolean {
  if (JWT.test(value)) return true
  return value.includes('"access_token"') || value.includes('"refresh_token"') || value.includes('"id_token"')
}

/** Drop token leftovers. The in-memory user store is not web storage, so this does not clear the session. */
export function sweepBrowserStorage(): void {
  for (const store of [window.localStorage, window.sessionStorage]) {
    const doomed: string[] = []
    for (let i = 0; i < store.length; i += 1) {
      const key = store.key(i)
      if (!key) continue
      const value = store.getItem(key) ?? ''
      if (key.startsWith('oidc.') || looksSecret(value)) {
        doomed.push(key)
      }
    }
    for (const key of doomed) store.removeItem(key)
  }
}
