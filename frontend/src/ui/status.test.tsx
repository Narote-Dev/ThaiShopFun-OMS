import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { OrderDisplayBadge } from './OrderDisplayBadge'
import { StatusBadge } from './StatusBadge'
import { ALL_STATUS_VALUES, resolveOrderDisplayStatus, resolveStatus } from './status'

afterEach(() => {
  cleanup()
})

describe('status mapping', () => {
  it('covers every known enum with a variant', () => {
    for (const { kind, value } of ALL_STATUS_VALUES) {
      const style = resolveStatus(value, kind)
      expect(style.variant).toBeTruthy()
      render(<StatusBadge value={value} kind={kind} />)
      if (value === 'NONE' && (kind === 'hold' || kind === 'hold_reason')) {
        expect(screen.getByText('—')).toBeInTheDocument()
      }
    }
  })

  it('maps combined order display status by priority', () => {
    expect(resolveOrderDisplayStatus({
      order_status: 'CANCELLED',
      fulfillment_status: 'READY_TO_PICK',
      hold_reason: 'OUT_OF_STOCK',
    }).label).toBe('CANCELLED')
    expect(resolveOrderDisplayStatus({
      order_status: 'ACTIVE',
      fulfillment_status: 'READY_TO_PICK',
      hold_reason: 'SKU_NOT_MAPPED',
    }).label).toBe('ON HOLD')
    expect(resolveOrderDisplayStatus({
      order_status: 'ACTIVE',
      fulfillment_status: 'READY_TO_PICK',
      hold_reason: 'NONE',
    }).label).toBe('READY_TO_PICK')
  })

  it('renders order display badge', () => {
    render(
      <OrderDisplayBadge
        order_status="ACTIVE"
        fulfillment_status="UNFULFILLED"
        hold_reason="NONE"
      />,
    )
    expect(screen.getByText('UNFULFILLED')).toBeInTheDocument()
  })
})
