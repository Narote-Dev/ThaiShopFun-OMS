/** Overlapping callers share one promise. A later call starts a new run. */
export function singleFlight<T>(fn: () => Promise<T>): () => Promise<T> {
  let inflight: Promise<T> | null = null
  return () => {
    if (!inflight) {
      inflight = fn().finally(() => {
        inflight = null
      })
    }
    return inflight
  }
}
