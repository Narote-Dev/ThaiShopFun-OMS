import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OutboxAdminPage from './OutboxAdminPage'

const event = {
  id: '11111111-1111-7111-8111-111111111111',
  aggregate_type: 'inventory',
  aggregate_id: 'sku-1',
  event_type: 'stock.updated',
  status: 'DEAD',
  attempts: 8,
}

describe('OutboxAdminPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    cleanup()
  })

  it('loads DEAD events and retries one', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return new Response(JSON.stringify({ id: event.id, status: 'PENDING', attempts: 0 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      const calls = fetchMock.mock.calls.filter((call) => call[1]?.method !== 'POST')
      const body = calls.length > 1 ? { events: [] } : { events: [event] }
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<OutboxAdminPage />)
    expect(screen.getByRole('button', { name: 'Load' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Access token'), { target: { value: 'memory-token' } })
    fireEvent.click(screen.getByRole('button', { name: 'Load' }))
    expect(await screen.findByText(/stock.updated/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('No DEAD events.')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/outbox/11111111-1111-7111-8111-111111111111/retry',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('shows the load error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('no', { status: 401 })),
    )
    render(<OutboxAdminPage />)
    fireEvent.change(screen.getByLabelText('Access token'), { target: { value: 'bad' } })
    fireEvent.click(screen.getByRole('button', { name: 'Load' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Load failed (401)')
  })
})
