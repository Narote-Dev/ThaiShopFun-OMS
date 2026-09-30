import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { configureApi, resetApiForTests } from '../api/client'
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
    resetApiForTests()
    configureApi({ getAccessToken: () => 'memory-token' })
  })

  afterEach(() => {
    cleanup()
    resetApiForTests()
    vi.restoreAllMocks()
  })

  it('loads DEAD events and retries one', async () => {
    const fetchImpl = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const headers = new Headers(init?.headers)
      expect(headers.get('Authorization')).toBe('Bearer memory-token')
      if (init?.method === 'POST') {
        return new Response(JSON.stringify({ id: event.id, status: 'PENDING', attempts: 0 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      const calls = fetchImpl.mock.calls.filter((call) => call[1]?.method !== 'POST')
      const body = calls.length > 1 ? { events: [] } : { events: [event] }
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    configureApi({ getAccessToken: () => 'memory-token', fetchImpl })

    render(<OutboxAdminPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Load' }))
    expect(await screen.findByText(/stock.updated/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('No DEAD events.')).toBeInTheDocument()
    expect(fetchImpl).toHaveBeenCalledWith(
      '/api/v1/outbox/11111111-1111-7111-8111-111111111111/retry',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('shows the load error', async () => {
    configureApi({
      getAccessToken: () => 'bad',
      refreshAccessToken: async () => {
        throw new Error('refresh failed')
      },
      fetchImpl: vi.fn(async () =>
        new Response(JSON.stringify({ error: 'UNAUTHORIZED', message: 'Invalid or expired token' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    })
    render(<OutboxAdminPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Load' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid or expired token')
  })

  it('disables retry while the shop is in grace', async () => {
    configureApi({
      getAccessToken: () => 'memory-token',
      fetchImpl: vi.fn(async () =>
        new Response(JSON.stringify({ events: [event] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    })
    render(<OutboxAdminPage readOnly />)
    fireEvent.click(screen.getByRole('button', { name: 'Load' }))
    expect(await screen.findByRole('button', { name: 'Retry' })).toBeDisabled()
  })

  it('shows a read-only message when a write is rejected with ENTITLEMENT_GRACE', async () => {
    configureApi({
      getAccessToken: () => 'memory-token',
      fetchImpl: vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
        if (init?.method === 'POST') {
          return new Response(
            JSON.stringify({ error: 'ENTITLEMENT_GRACE', message: 'Membership is in a grace period' }),
            { status: 403, headers: { 'Content-Type': 'application/json' } },
          )
        }
        return new Response(JSON.stringify({ events: [event] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }),
    })
    render(<OutboxAdminPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Load' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This shop is read-only until membership is renewed.',
    )
  })
})
