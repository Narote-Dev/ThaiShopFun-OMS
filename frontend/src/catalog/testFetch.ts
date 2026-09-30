import { vi } from 'vitest'

export type Call = { url: string; method: string; init: RequestInit | undefined }

type Handler = (call: Call) => { status?: number; body?: unknown } | undefined

/** fetch stub: the first handler that returns a response wins. Records every call. */
export function stubFetch(handler: Handler) {
  const calls: Call[] = []
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const call = { url: String(input), method: init?.method ?? 'GET', init }
    calls.push(call)
    const reply = handler(call) ?? { status: 404, body: { error: 'NOT_FOUND', message: 'Not found' } }
    const status = reply.status ?? 200
    if (status === 204) return new Response(null, { status })
    return new Response(JSON.stringify(reply.body ?? {}), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
  })
  return { fetchImpl, calls }
}

export const sku = (overrides: Record<string, unknown> = {}) => ({
  id: '0190aaaa-0000-7000-8000-000000000001',
  product_id: 'p-1',
  product_name: 'Mug',
  sku_code: 'MUG-RED',
  name: 'Red mug',
  barcode: '8850001112223',
  weight_g: 350,
  is_bundle: false,
  on_hand: 4,
  reserved: 1,
  component_count: 0,
  ...overrides,
})
