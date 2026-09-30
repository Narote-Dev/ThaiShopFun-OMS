import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { configureApi, resetApiForTests } from '../api/client'
import { stubFetch } from '../catalog/testFetch'
import WarehousesPage from './WarehousesPage'

const list = {
  items: [
    { id: 'w-main', code: 'MAIN', name: 'Main warehouse', address: null, is_default: true },
    { id: 'w-bkk', code: 'BKK', name: 'Bangkok', address: null, is_default: false },
  ],
}

describe('WarehousesPage', () => {
  beforeEach(() => resetApiForTests())
  afterEach(() => {
    cleanup()
    resetApiForTests()
    vi.restoreAllMocks()
  })

  it('sets a new default and creates a warehouse', async () => {
    const { fetchImpl, calls } = stubFetch(({ method, url }) => {
      if (method === 'GET') return { body: list }
      if (url.endsWith('/default')) return { body: { ...list.items[1], is_default: true } }
      if (method === 'POST') return { status: 201, body: { id: 'w-new', code: 'CNX', name: 'Chiang Mai', address: null, is_default: false } }
      return undefined
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<WarehousesPage canWrite />)
    expect(await screen.findByLabelText('Code of MAIN')).toHaveValue('MAIN')
    const setDefault = screen.getAllByRole('button', { name: 'Set default' })
    expect(setDefault[0]).toBeDisabled()
    fireEvent.click(setDefault[1])
    await waitFor(() => expect(calls.some((c) => c.url === '/api/v1/warehouses/w-bkk/default' && c.method === 'POST')).toBe(true))

    fireEvent.change(screen.getByLabelText('Code'), { target: { value: 'CNX' } })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Chiang Mai' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add warehouse' }))
    await waitFor(() => {
      const post = calls.find((c) => c.url === '/api/v1/warehouses' && c.method === 'POST')
      expect(JSON.parse(String(post?.init?.body))).toEqual({ code: 'CNX', name: 'Chiang Mai' })
    })
  })

  it('shows the in-use conflict', async () => {
    const { fetchImpl } = stubFetch(({ method }) => {
      if (method === 'GET') return { body: list }
      return { status: 409, body: { error: 'WAREHOUSE_IN_USE', message: 'Still referenced by other records' } }
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<WarehousesPage canWrite />)
    const deletes = await screen.findAllByRole('button', { name: 'Delete' })
    fireEvent.click(deletes[1])
    expect(await screen.findByRole('alert')).toHaveTextContent('WAREHOUSE_IN_USE')
  })

  it('is read-only for STAFF or GRACE', async () => {
    const { fetchImpl } = stubFetch(() => ({ body: list }))
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<WarehousesPage canWrite={false} />)
    expect(await screen.findByText('Bangkok')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Set default' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Add warehouse' })).toBeNull()
  })
})
