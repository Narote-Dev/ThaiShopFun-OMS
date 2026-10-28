import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import StockDriftPage from './StockDriftPage'
import { driftApi, type DriftIssue } from './api'

const issue: DriftIssue = {
  id: 'issue', status: 'OPEN', created_at: '2026-10-28T01:00:00Z', updated_at: '2026-10-28T02:00:00Z',
  details: { run_id: 'run', listings: [
    { listing_id: 'a', external_listing_id: 'TSF-A', channel_account_id: 'ca', sku_ids: ['SKU-A'], expected: 10, seen: 3, first_seen_at: '2026-10-28T01:00:00Z', last_seen_at: '2026-10-28T02:00:00Z', repush_enqueued_at: '2026-10-28T02:00:00Z' },
    { listing_id: 'b', external_listing_id: 'TSF-B', channel_account_id: 'ca', sku_ids: ['SKU-B'], expected: 8, seen: 4, first_seen_at: '2026-10-28T01:00:00Z', last_seen_at: '2026-10-28T02:00:00Z', repush_enqueued_at: '2026-10-28T02:00:00Z' },
  ] },
}
afterEach(() => { cleanup(); vi.restoreAllMocks() })
describe('stock drift', () => {
  it('shows per-listing rows from the single issue and refreshes after an admin run', async () => {
    const list = vi.spyOn(driftApi, 'list').mockResolvedValue({ items: [issue] })
    const run = vi.spyOn(driftApi, 'run').mockResolvedValue({ run_id: 'next', drifting: 2, repush_events: 1, superseded: false })
    render(<StockDriftPage canRun />)
    expect(await screen.findByText('TSF-A')).toBeVisible()
    expect(screen.getByText('TSF-B')).toBeVisible()
    expect(screen.getAllByText('รอตรวจสอบ')).toHaveLength(3)
    expect(screen.getByText('ยอดที่ OMS ส่ง')).toBeVisible()
    expect(screen.getByText('ยอดที่ TSF แสดง')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'ตรวจสอบตอนนี้' }))
    await waitFor(() => expect(run).toHaveBeenCalledOnce())
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2))
  })
  it.each(['ACK', 'RESOLVED'] as const)('translates %s and allows status filtering for readers', async (status) => {
    const list = vi.spyOn(driftApi, 'list').mockResolvedValue({ items: [{ ...issue, status }] })
    render(<StockDriftPage canRun={false} />)
    expect(await screen.findByText('TSF-A')).toBeVisible()
    expect(screen.queryByRole('button', { name: 'ตรวจสอบตอนนี้' })).toBeNull()
    expect(screen.getAllByText(status === 'ACK' ? 'รับทราบแล้ว' : 'แก้ไขแล้ว').length).toBeGreaterThan(1)
    fireEvent.change(screen.getByRole('combobox', { name: 'สถานะปัญหา' }), { target: { value: status } })
    await waitFor(() => expect(list).toHaveBeenLastCalledWith(status))
  })
  it('shows Thai errors and supports retry', async () => {
    const list = vi.spyOn(driftApi, 'list').mockRejectedValueOnce(new Error('raw English diagnostic')).mockResolvedValue({ items: [] })
    render(<StockDriftPage canRun={false} />)
    expect(await screen.findByText('ตรวจสอบสต็อกไม่สำเร็จ กรุณาลองอีกครั้ง')).toBeVisible()
    expect(screen.queryByText('raw English diagnostic')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'ลองอีกครั้ง' }))
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('ไม่พบปัญหาสต็อกในสถานะนี้')).toBeVisible()
  })
})
