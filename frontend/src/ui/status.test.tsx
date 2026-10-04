import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StatusBadge } from './StatusBadge'
import { ALL_STATUS_VALUES, resolveStatus } from './status'

describe('status mapping', () => {
  it('covers every known enum with a variant', () => {
    for (const { kind, value } of ALL_STATUS_VALUES) {
      const style = resolveStatus(value, kind)
      expect(style.variant).toBeTruthy()
      render(<StatusBadge value={value} kind={kind} />)
      if (value === 'NONE' && kind === 'hold') {
        expect(screen.getByText('—')).toBeInTheDocument()
      } else {
        expect(screen.getByText(style.label ?? value)).toBeInTheDocument()
      }
    }
  })

  it('maps fulfilment and payment method colours', () => {
    expect(resolveStatus('READY_TO_PICK', 'fulfillment').variant).toBe('success')
    expect(resolveStatus('SKU_NOT_MAPPED', 'hold').variant).toBe('warning')
    expect(resolveStatus('COD', 'payment_method').variant).toBe('violet')
    expect(resolveStatus('TSF', 'channel').variant).toBe('channel')
  })
})
