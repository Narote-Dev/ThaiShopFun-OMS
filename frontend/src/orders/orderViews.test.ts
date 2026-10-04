import { describe, expect, it } from 'vitest'
import { filtersForTab, tabFromFilters } from './orderViews'

describe('orderViews tabs', () => {
  const dates = { ordered_from: '2026-03-01', ordered_to: '2026-03-31' }

  it('filtersForTab preserves date scope', () => {
    expect(filtersForTab('ready', dates).ordered_from).toBe('2026-03-01')
    expect(filtersForTab('ready', dates).fulfillment_status).toBe('READY_TO_PICK')
    expect(filtersForTab('hold', dates).hold_reason).toBe('ANY')
    expect(filtersForTab('cancelled', dates).order_status).toBe('CANCELLED')
  })

  it('tabFromFilters matches single-dimension tab presets', () => {
    expect(tabFromFilters(filtersForTab('all', dates))).toBe('all')
    expect(tabFromFilters(filtersForTab('ready', dates))).toBe('ready')
    expect(tabFromFilters(filtersForTab('hold', dates))).toBe('hold')
    expect(tabFromFilters(filtersForTab('cancelled', dates))).toBe('cancelled')
    expect(
      tabFromFilters({
        ...filtersForTab('all', dates),
        fulfillment_status: 'PICKING',
      }),
    ).toBe('all')
  })
})
