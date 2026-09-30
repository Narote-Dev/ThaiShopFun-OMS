export function normalizeHash(hash: string): string {
  if (!hash || hash === '#' || hash === '#/') return '#/'
  return hash
}

/** In-app hash only. Rejects query strings and off-site targets. */
export function safeReturn(hash: string): string {
  if (/^#\/[a-z0-9/-]*$/.test(hash)) return hash
  return '#/'
}

export function stripAuthQuery(hash: string): void {
  const url = new URL(window.location.href)
  url.searchParams.delete('code')
  url.searchParams.delete('state')
  url.searchParams.delete('session_state')
  url.searchParams.delete('iss')
  const search = url.searchParams.toString()
  window.history.replaceState(null, '', `${url.pathname}${search ? `?${search}` : ''}${hash}`)
  // replaceState does not emit hashchange. The shell is already mounted during the callback.
  window.dispatchEvent(new HashChangeEvent('hashchange'))
}
