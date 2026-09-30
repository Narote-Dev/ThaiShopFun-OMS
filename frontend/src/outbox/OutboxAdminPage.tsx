import { useState } from 'react'
import { apiRequest, ApiError } from '../api/client'

type DeadEvent = {
  id: string
  aggregate_type: string
  aggregate_id: string
  event_type: string
  status: string
  attempts: number
}

const READ_ONLY = 'This shop is read-only until membership is renewed.'

export default function OutboxAdminPage({ readOnly = false }: { readOnly?: boolean }) {
  const [events, setEvents] = useState<DeadEvent[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [loaded, setLoaded] = useState(false)

  async function load() {
    setBusy(true)
    setError('')
    try {
      const body = await apiRequest<{ events: DeadEvent[] }>('/api/v1/outbox')
      setEvents(body.events)
      setLoaded(true)
    } catch (err) {
      setEvents([])
      setError(messageFor(err, 'Load failed'))
    } finally {
      setBusy(false)
    }
  }

  async function retry(id: string) {
    setBusy(true)
    setError('')
    try {
      await apiRequest(`/api/v1/outbox/${id}/retry`, { method: 'POST' })
      await load()
    } catch (err) {
      setError(messageFor(err, 'Retry failed'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <main>
      <h1>Dead outbox</h1>
      <p>OWNER or ADMIN can send a DEAD event again. The session token stays in memory.</p>
      <button type="button" onClick={() => void load()} disabled={busy}>
        Load
      </button>
      {error ? <p role="alert">{error}</p> : null}
      <ul>
        {events.map((event) => (
          <li key={event.id}>
            <span>
              {event.event_type} · {event.aggregate_id} · {event.attempts} attempts
            </span>
            <button type="button" onClick={() => void retry(event.id)} disabled={busy || readOnly}>
              Retry
            </button>
          </li>
        ))}
      </ul>
      {loaded && events.length === 0 && !error ? <p>No DEAD events.</p> : null}
    </main>
  )
}

function messageFor(err: unknown, fallback: string): string {
  if (err instanceof ApiError && err.code === 'ENTITLEMENT_GRACE') return READ_ONLY
  if (err instanceof ApiError) return err.message || `${fallback} (${err.status})`
  return fallback
}
