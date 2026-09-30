import { apiRequest, ApiError } from '../api/client'

export type Page<T> = { items: T[]; total: number; limit: number; offset: number }

export type Product = {
  id: string
  name: string
  status: 'ACTIVE' | 'INACTIVE'
  sku_count: number
}

export type Component = { component_sku_id: string; sku_code: string; name: string; qty: number }

export type Sku = {
  id: string
  product_id: string
  product_name: string
  sku_code: string
  name: string
  barcode: string | null
  weight_g: number | null
  is_bundle: boolean
  on_hand: number | null
  reserved: number | null
  component_count: number
  components?: Component[]
}

export type SkuInput = {
  product_id?: string
  product_name?: string
  sku_code: string
  name: string
  barcode: string | null
  weight_g: number | null
  is_bundle: boolean
}

export type ComponentInput = { component_sku_code: string; qty: number }

export type ImportRowError = { row: number; column: string | null; error: string }

export type ImportResult = {
  rows: number
  products_created: number
  skus_created: number
  skus_updated: number
  skus_unchanged: number
  bundles_replaced: number
  elapsed_ms: number
}

export type Warehouse = {
  id: string
  code: string
  name: string
  address: Record<string, unknown> | null
  is_default: boolean
}

/** 422 IMPORT_INVALID carries the per-row list next to the usual error fields. */
export class ImportInvalidError extends Error {
  readonly errors: ImportRowError[]

  constructor(message: string, errors: ImportRowError[]) {
    super(message)
    this.name = 'ImportInvalidError'
    this.errors = errors
  }
}

const json = (body: unknown): RequestInit => ({ body: JSON.stringify(body) })

export const catalogApi = {
  listSkus: (q: string, limit: number, offset: number) => {
    const params = new URLSearchParams({ limit: String(limit), offset: String(offset) })
    if (q.trim()) params.set('q', q.trim())
    return apiRequest<Page<Sku>>(`/api/v1/skus?${params.toString()}`)
  },
  getSku: (id: string) => apiRequest<Sku>(`/api/v1/skus/${id}`),
  createSku: (input: SkuInput) => apiRequest<Sku>('/api/v1/skus', { method: 'POST', ...json(input) }),
  updateSku: (id: string, input: SkuInput) =>
    apiRequest<Sku>(`/api/v1/skus/${id}`, { method: 'PUT', ...json(input) }),
  deleteSku: (id: string) => apiRequest<void>(`/api/v1/skus/${id}`, { method: 'DELETE' }),
  replaceComponents: (id: string, components: ComponentInput[]) =>
    apiRequest<Sku>(`/api/v1/skus/${id}/components`, { method: 'PUT', ...json(components) }),
  listProducts: () => apiRequest<Page<Product>>('/api/v1/products?limit=200'),
  createProduct: (name: string) =>
    apiRequest<Product>('/api/v1/products', { method: 'POST', ...json({ name }) }),
  updateProduct: (id: string, name: string, status: Product['status']) =>
    apiRequest<Product>(`/api/v1/products/${id}`, { method: 'PUT', ...json({ name, status }) }),
  archiveProduct: (id: string) =>
    apiRequest<Product>(`/api/v1/products/${id}/archive`, { method: 'POST' }),
  listWarehouses: () => apiRequest<{ items: Warehouse[] }>('/api/v1/warehouses'),
  createWarehouse: (code: string, name: string) =>
    apiRequest<Warehouse>('/api/v1/warehouses', { method: 'POST', ...json({ code, name }) }),
  updateWarehouse: (id: string, code: string, name: string, address: Warehouse['address']) =>
    apiRequest<Warehouse>(`/api/v1/warehouses/${id}`, { method: 'PUT', ...json({ code, name, address }) }),
  setDefaultWarehouse: (id: string) =>
    apiRequest<Warehouse>(`/api/v1/warehouses/${id}/default`, { method: 'POST' }),
  deleteWarehouse: (id: string) => apiRequest<void>(`/api/v1/warehouses/${id}`, { method: 'DELETE' }),
  importCsv: async (file: File): Promise<ImportResult> => {
    const form = new FormData()
    form.append('file', file)
    try {
      return await apiRequest<ImportResult>('/api/v1/catalog/import', { method: 'POST', body: form })
    } catch (err) {
      if (err instanceof ApiError && err.code === 'IMPORT_INVALID') {
        throw new ImportInvalidError(err.message, Array.isArray(err.details) ? (err.details as ImportRowError[]) : [])
      }
      throw err
    }
  },
}

const READ_ONLY = 'This shop is read-only until membership is renewed.'

export function messageFor(err: unknown, fallback: string): string {
  if (err instanceof ApiError && err.code === 'ENTITLEMENT_GRACE') return READ_ONLY
  if (err instanceof ApiError && err.code === 'FORBIDDEN') return 'Only an OWNER or ADMIN can change the catalog.'
  if (err instanceof ApiError) return `${err.message || fallback} (${err.code})`
  if (err instanceof Error && err.message) return err.message
  return fallback
}
