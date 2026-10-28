import { apiRequest } from '../api/client'

export type DriftListing = {
  listing_id: string
  channel_account_id: string
  external_listing_id: string
  sku_ids: string[]
  expected: number
  seen: number
  first_seen_at: string
  last_seen_at: string
  repush_enqueued_at: string
}
export type DriftIssue = {
  id: string
  status: 'OPEN' | 'ACK' | 'RESOLVED'
  details: { listings: DriftListing[]; run_id: string }
  created_at: string
  updated_at: string
}
export const driftApi = {
  list(status: string): Promise<{ items: DriftIssue[] }> {
    return apiRequest(`/api/v1/stock-drift${status ? `?status=${encodeURIComponent(status)}` : ''}`)
  },
  run(): Promise<{ run_id: string; drifting: number; repush_events: number; superseded: boolean }> {
    return apiRequest('/api/v1/stock-drift/runs', { method: 'POST', body: '{}' })
  },
}
