import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { configureApi, resetApiForTests } from '../api/client'
import BundleEditor from './BundleEditor'
import ImportPage from './ImportPage'
import SkuFormPage from './SkuFormPage'
import SkuListPage, { PAGE_SIZE } from './SkuListPage'
import { catalogApi } from './api'
import { stubFetch, sku } from './testFetch'

const products = { items: [{ id: 'p-1', name: 'Mug', status: 'ACTIVE', sku_count: 1 }], total: 1, limit: 200, offset: 0 }

beforeEach(() => {
  resetApiForTests()
})

afterEach(() => {
  cleanup()
  resetApiForTests()
  vi.restoreAllMocks()
  window.location.hash = ''
})

describe('SkuListPage', () => {
  it('searches and pages through SKUs', async () => {
    const { fetchImpl, calls } = stubFetch(({ url }) => {
      const offset = Number(new URL(url, 'http://x').searchParams.get('offset'))
      return { body: { items: [sku({ sku_code: `MUG-${offset}` })], total: PAGE_SIZE + 1, limit: PAGE_SIZE, offset } }
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<SkuListPage canWrite />)
    expect(await screen.findByRole('link', { name: 'MUG-0' })).toHaveAttribute('href', expect.stringContaining('#/catalog/skus/'))
    expect(screen.getByRole('link', { name: 'New SKU' })).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Search SKUs'), { target: { value: 'mug' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => expect(calls.some((c) => c.url.includes('q=mug'))).toBe(true))

    fireEvent.click(await screen.findByRole('button', { name: 'Next' }))
    expect(await screen.findByRole('link', { name: `MUG-${PAGE_SIZE}` })).toBeInTheDocument()
    expect(calls.at(-1)?.url).toContain(`offset=${PAGE_SIZE}`)
    expect(calls.at(-1)?.url).toContain('q=mug')
  })

  it('hides New SKU for read-only users', async () => {
    const { fetchImpl } = stubFetch(() => ({ body: { items: [], total: 0, limit: 25, offset: 0 } }))
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<SkuListPage canWrite={false} />)
    expect(await screen.findByText('No SKUs found.')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'New SKU' })).toBeNull()
  })
})

describe('SkuFormPage', () => {
  it('creates a SKU with a new product and opens it', async () => {
    const { fetchImpl, calls } = stubFetch(({ url, method }) => {
      if (url.startsWith('/api/v1/products')) return { body: { ...products, items: [] } }
      if (url === '/api/v1/skus' && method === 'POST') return { status: 201, body: sku({ id: 'new-id' }) }
      return undefined
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<SkuFormPage id={null} canWrite />)
    fireEvent.change(await screen.findByLabelText('New product name'), { target: { value: 'Mug' } })
    fireEvent.change(screen.getByLabelText('SKU code'), { target: { value: 'MUG-RED' } })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Red mug' } })
    fireEvent.change(screen.getByLabelText('Weight (g)'), { target: { value: '350' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create SKU' }))
    await waitFor(() => expect(window.location.hash).toBe('#/catalog/skus/new-id'))
    const post = calls.find((c) => c.method === 'POST')
    expect(JSON.parse(String(post?.init?.body))).toEqual({
      sku_code: 'MUG-RED',
      name: 'Red mug',
      barcode: null,
      weight_g: 350,
      is_bundle: false,
      product_name: 'Mug',
    })
  })

  it('shows the server error code for a duplicate sku_code', async () => {
    const { fetchImpl } = stubFetch(({ url, method }) => {
      if (url.startsWith('/api/v1/products')) return { body: products }
      if (method === 'POST') return { status: 409, body: { error: 'SKU_CODE_EXISTS', message: 'sku_code already exists' } }
      return undefined
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<SkuFormPage id={null} canWrite />)
    fireEvent.change(await screen.findByLabelText('SKU code'), { target: { value: 'MUG-RED' } })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Red mug' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create SKU' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('sku_code already exists (SKU_CODE_EXISTS)')
  })

  it('is read-only without write access and shows the bundle editor for bundles', async () => {
    const bundle = sku({
      id: 'b-1',
      is_bundle: true,
      on_hand: null,
      components: [{ component_sku_id: 'c-1', sku_code: 'MUG-RED', name: 'Red mug', qty: 2 }],
    })
    const { fetchImpl } = stubFetch(({ url }) => {
      if (url.startsWith('/api/v1/products')) return { body: products }
      if (url === '/api/v1/skus/b-1') return { body: bundle }
      return undefined
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<SkuFormPage id="b-1" canWrite={false} />)
    expect(await screen.findByLabelText('Component 1 code')).toHaveValue('MUG-RED')
    expect(screen.getByLabelText('SKU code')).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Save components' })).toBeNull()
  })
})

describe('BundleEditor', () => {
  it('replaces the component list and shows NESTED_BUNDLE', async () => {
    let attempt = 0
    const { fetchImpl, calls } = stubFetch(({ method }) => {
      if (method !== 'PUT') return undefined
      attempt += 1
      if (attempt === 1) return { status: 422, body: { error: 'NESTED_BUNDLE', message: 'A bundle cannot contain another bundle' } }
      return { body: sku({ id: 'b-1', is_bundle: true, components: [{ component_sku_id: 'c', sku_code: 'A', name: 'A', qty: 3 }] }) }
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    const onSaved = vi.fn()
    render(<BundleEditor sku={sku({ id: 'b-1', is_bundle: true, components: [] }) as never} canWrite onSaved={onSaved} />)
    fireEvent.click(screen.getByRole('button', { name: 'Add component' }))
    fireEvent.change(screen.getByLabelText('Component 1 code'), { target: { value: 'SET-2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save components' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('NESTED_BUNDLE')

    fireEvent.change(screen.getByLabelText('Component 1 code'), { target: { value: 'A' } })
    fireEvent.change(screen.getByLabelText('Qty'), { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save components' }))
    expect(await screen.findByText('Components saved.')).toBeInTheDocument()
    expect(onSaved).toHaveBeenCalled()
    expect(calls.at(-1)?.url).toBe('/api/v1/skus/b-1/components')
    expect(JSON.parse(String(calls.at(-1)?.init?.body))).toEqual([{ component_sku_code: 'A', qty: 3 }])
  })
})

describe('ImportPage', () => {
  function choose(content: string) {
    const file = new File([content], 'catalog.csv', { type: 'text/csv' })
    fireEvent.change(screen.getByLabelText('CSV file'), { target: { files: [file] } })
  }

  it('uploads multipart and lists every bad row', async () => {
    const { fetchImpl, calls } = stubFetch(() => ({
      status: 422,
      body: {
        error: 'IMPORT_INVALID',
        message: '2 rows have errors; nothing was imported',
        trace_id: 'abc',
        errors: [
          { row: 3, column: 'sku_code', error: 'is required' },
          { row: 5, column: 'components', error: 'NESTED_BUNDLE: component SET-1 is a bundle' },
        ],
      },
    }))
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<ImportPage canWrite />)
    choose('product_name,sku_code,sku_name\n')
    fireEvent.click(screen.getByRole('button', { name: 'Import' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('nothing was imported')
    const table = screen.getByRole('table', { name: 'Row errors' })
    expect(table).toHaveTextContent('3sku_codeis required')
    expect(table).toHaveTextContent('NESTED_BUNDLE: component SET-1 is a bundle')
    const init = calls[0].init
    expect(init?.body).toBeInstanceOf(FormData)
    expect(new Headers(init?.headers).has('Content-Type')).toBe(false)
  })

  it('shows the import summary', async () => {
    const { fetchImpl } = stubFetch(() => ({
      body: { rows: 3, products_created: 2, skus_created: 3, skus_updated: 0, skus_unchanged: 0, bundles_replaced: 1, elapsed_ms: 40 },
    }))
    configureApi({ getAccessToken: () => 't', fetchImpl })
    render(<ImportPage canWrite />)
    choose('x')
    fireEvent.click(screen.getByRole('button', { name: 'Import' }))
    expect(await screen.findByText(/Imported 3 rows: 3 created/)).toBeInTheDocument()
  })

  it('has no upload form without write access', () => {
    render(<ImportPage canWrite={false} />)
    expect(screen.queryByLabelText('CSV file')).toBeNull()
  })
})

describe('catalogApi.listProducts', () => {
  it('follows every page past the 200 limit', async () => {
    const all = Array.from({ length: 250 }, (_, i) => ({ id: `p-${i}`, name: `P ${i}`, status: 'ACTIVE', sku_count: 0 }))
    const { fetchImpl, calls } = stubFetch(({ url }) => {
      const params = new URL(url, 'http://x').searchParams
      const offset = Number(params.get('offset'))
      return { body: { items: all.slice(offset, offset + 200), total: all.length, limit: 200, offset } }
    })
    configureApi({ getAccessToken: () => 't', fetchImpl })
    const page = await catalogApi.listProducts()
    expect(page.items).toHaveLength(250)
    expect(page.items.at(-1)?.id).toBe('p-249')
    expect(calls.map((c) => c.url)).toEqual([
      '/api/v1/products?limit=200&offset=0',
      '/api/v1/products?limit=200&offset=200',
    ])
  })
})
