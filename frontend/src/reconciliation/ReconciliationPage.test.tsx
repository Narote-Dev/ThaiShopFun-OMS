import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ReconciliationPage from './ReconciliationPage'
import { reconciliationApi, type ReconciliationIssue } from './api'

const issue: ReconciliationIssue = {
  id: 'issue-1',
  rule: 'SHIP_BY_LATE',
  status: 'OPEN',
  order_id: 'order',
  external_order_id: 'TSF-1001',
  details: {},
  created_at: '2026-10-28T01:00:00Z',
  updated_at: '2026-10-28T02:00:00Z',
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('reconciliation page', () => {
  it('lists issues, runs reconcile, and patches status in Thai', async () => {
    const list = vi.spyOn(reconciliationApi, 'list').mockResolvedValue({ items: [issue] })
    const run = vi.spyOn(reconciliationApi, 'run').mockResolvedValue({ run_id: 'run', issues_found: 1, by_rule: {} })
    const patch = vi.spyOn(reconciliationApi, 'patch').mockResolvedValue({ id: issue.id, status: 'ACK' })
    render(<ReconciliationPage canRun />)
    expect(await screen.findByText('TSF-1001')).toBeVisible()
    expect(screen.getAllByText('เลยกำหนดส่ง').length).toBeGreaterThan(0)
    fireEvent.click(screen.getByRole('button', { name: 'รับทราบ' }))
    await waitFor(() => expect(patch).toHaveBeenCalledWith(issue.id, 'ACK'))
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2))
    fireEvent.click(screen.getByRole('button', { name: 'รันกระทบยอดตอนนี้' }))
    await waitFor(() => expect(run).toHaveBeenCalledOnce())
  })

  it('hides run and patch for readers', async () => {
    vi.spyOn(reconciliationApi, 'list').mockResolvedValue({ items: [{ ...issue, status: 'ACK' }] })
    render(<ReconciliationPage canRun={false} />)
    expect(await screen.findByText('TSF-1001')).toBeVisible()
    expect(screen.queryByRole('button', { name: 'รันกระทบยอดตอนนี้' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'รับทราบ' })).toBeNull()
  })
})
