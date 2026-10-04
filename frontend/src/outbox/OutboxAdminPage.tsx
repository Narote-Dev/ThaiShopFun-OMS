import { useState } from 'react'
import { apiRequest, ApiError } from '../api/client'
import { Button } from '../ui/Button'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'

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
    <PageContent>
      <PageHeader
        title={
          <>
            <span aria-hidden="true">Dead outbox</span>
            <span className="sr-only">Dead outbox</span>
          </>
        }
      />
      <p className="mt-4 text-[13px] text-stone-600">OWNER or ADMIN can send a DEAD event again. The session token stays in memory.</p>
      <Button type="button" variant="primary" className="mt-4" onClick={() => void load()} disabled={busy}>
        Load
      </Button>
      {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : null}
      <ul className="mt-4 space-y-3">
        {events.map((event) => (
          <li key={event.id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-stone-200 bg-white px-4 py-3 text-[13px]">
            <span>
              {event.event_type} · {event.aggregate_id} · {event.attempts} attempts
            </span>
            <Button type="button" size="sm" variant="secondary" onClick={() => void retry(event.id)} disabled={busy || readOnly}>
              Retry
            </Button>
          </li>
        ))}
      </ul>
      {loaded && events.length === 0 && !error ? <p className="mt-4 text-[13px] text-stone-600">No DEAD events.</p> : null}
    </PageContent>
  )
}

function messageFor(err: unknown, fallback: string): string {
  if (err instanceof ApiError && err.code === 'ENTITLEMENT_GRACE') return READ_ONLY
  if (err instanceof ApiError) return err.message || `${fallback} (${err.status})`
  return fallback
}
