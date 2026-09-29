import { useState } from 'react'

type DeadEvent = {
  id: string
  aggregate_type: string
  aggregate_id: string
  event_type: string
  status: string
  attempts: number
}

export default function OutboxAdminPage() {
  const [token, setToken] = useState('')
  const [events, setEvents] = useState<DeadEvent[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [loaded, setLoaded] = useState(false)

  async function load() {
    setBusy(true)
    setError('')
    try {
      const response = await fetch('/api/v1/outbox', {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!response.ok) {
        setError(`Load failed (${response.status})`)
        setEvents([])
        return
      }
      const body = (await response.json()) as { events: DeadEvent[] }
      setEvents(body.events)
      setLoaded(true)
    } catch {
      setError('Load failed')
      setEvents([])
    } finally {
      setBusy(false)
    }
  }

  async function retry(id: string) {
    setBusy(true)
    setError('')
    try {
      const response = await fetch(`/api/v1/outbox/${id}/retry`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!response.ok) {
        setError(`Retry failed (${response.status})`)
        return
      }
      await load()
    } catch {
      setError('Retry failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main>
      <p className="eyebrow">ThaiShopFun</p>
      <h1>Dead outbox</h1>
      <p>OWNER or ADMIN can send a DEAD event again. The token stays in memory.</p>
      <label>
        Access token
        <input
          type="password"
          value={token}
          autoComplete="off"
          onChange={(event) => setToken(event.target.value)}
        />
      </label>
      <button type="button" onClick={() => void load()} disabled={busy || token.trim() === ''}>
        Load
      </button>
      {error ? <p role="alert">{error}</p> : null}
      <ul>
        {events.map((event) => (
          <li key={event.id}>
            <span>
              {event.event_type} · {event.aggregate_id} · {event.attempts} attempts
            </span>
            <button type="button" onClick={() => void retry(event.id)} disabled={busy}>
              Retry
            </button>
          </li>
        ))}
      </ul>
      {loaded && events.length === 0 && !error ? <p>No DEAD events.</p> : null}
    </main>
  )
}
