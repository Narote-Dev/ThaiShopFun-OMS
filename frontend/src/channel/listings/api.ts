import { apiRequest, ApiError } from '../../api/client'
import type { Page } from '../../catalog/api'

export type ChannelListing = {
  id: string
  channel_account_id: string
  external_sku_id: string
  seller_sku: string | null
  name: string | null
  sku_id: string | null
  sku_code: string | null
  sku_name: string | null
  mapping_source: string | null
  mapped_at: string | null
  removed_at: string | null
  stock_control: boolean
  held_orders: number
}

export type ReevalSummary = {
  released: number
  out_of_stock: number
  still_held: number
  deferred: number
}

export type MappingPutResponse = {
  listing: ChannelListing
  reevaluation: ReevalSummary
}

export type ListingSyncResponse = {
  fetched: number
  created: number
  updated: number
  auto_mapped: number
  revived: number
  removed: number
  removal_skipped: boolean
  reevaluated_orders: number
  deferred: number
}

export type ChannelAccount = {
  id: string
  channel: string
  external_shop_id: string
  status: string
}

export const listingsApi = {
  listAccounts(): Promise<{ items: ChannelAccount[] }> {
    return apiRequest('/api/v1/channel-accounts')
  },
  list(
    channelAccountId: string,
    mapped: boolean | null,
    removedOnly: boolean,
    q: string,
    limit: number,
    offset: number,
    stockControl: boolean | null = null,
  ): Promise<Page<ChannelListing> & { mode?: string; allowlist_coverage_pct?: number }> {
    const params = new URLSearchParams()
    params.set('channel_account_id', channelAccountId)
    params.set('limit', String(limit))
    params.set('offset', String(offset))
    if (mapped !== null) params.set('mapped', mapped ? 'true' : 'false')
    if (removedOnly) params.set('removed', 'true')
    if (q) params.set('q', q)
    if (stockControl !== null) params.set('stock_control', String(stockControl))
    return apiRequest(`/api/v1/channel-listings?${params}`)
  },
  setStockControl(listingId: string, enabled: boolean): Promise<{ changed: number }> {
    return apiRequest(`/api/v1/channel-listings/${listingId}/stock-control`, {
      method: 'PUT',
      body: JSON.stringify({ enabled }),
    })
  },
  bulkStockControl(
    channelAccountId: string,
    listingIds: string[],
    enabled: boolean,
  ): Promise<{ changed: number }> {
    return apiRequest('/api/v1/channel-listings/stock-control', {
      method: 'POST',
      body: JSON.stringify({ channel_account_id: channelAccountId, listing_ids: listingIds, enabled }),
    })
  },
  putMapping(listingId: string, skuId: string): Promise<MappingPutResponse> {
    return apiRequest(`/api/v1/channel-listings/${listingId}/mapping`, {
      method: 'PUT',
      body: JSON.stringify({ sku_id: skuId }),
    })
  },
  deleteMapping(listingId: string): Promise<ChannelListing> {
    return apiRequest(`/api/v1/channel-listings/${listingId}/mapping`, { method: 'DELETE' })
  },
  syncListings(channelAccountId: string): Promise<ListingSyncResponse> {
    return apiRequest(`/api/v1/channel-accounts/${channelAccountId}/listing-syncs`, {
      method: 'POST',
      body: '{}',
    })
  },
}

export function listingsMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError) return err.message || fallback
  return fallback
}
