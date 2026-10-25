import { cleanup, fireEvent, render, screen, within, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { listingsApi, type ChannelListing } from './api'
import ListingsPage from './ListingsPage'
import type { Me } from '../../auth/AuthContext'

const owner: Me = { tenant: { id: 't', name: 'ร้าน', membership_tier: 'PRO', tsf_shop_id: 'shop' }, role: 'OWNER', entitlement: { status: 'ACTIVE', expires_at: null, ent_ver: 1 } }
const listing = { id: 'l', external_sku_id: 'L-demo-ready', sku_id: 's', stock_control: false, held_orders: 0 } as ChannelListing
function setup(mode = 'CONTROL', me = owner, stockControl = false) {
  window.location.hash = '#/channel/listings?channel_account_id=ca&mapped=all'
  vi.spyOn(listingsApi, 'listAccounts').mockResolvedValue({ items: [] })
  vi.spyOn(listingsApi, 'list').mockResolvedValue({ items: [{ ...listing, stock_control: stockControl }], total: 1, limit: 25, offset: 0, mode, allowlist_coverage_pct: 0 })
  const bulk = vi.spyOn(listingsApi, 'bulkStockControl').mockResolvedValue({ changed: 1 })
  const single = vi.spyOn(listingsApi, 'setStockControl').mockResolvedValue({ changed: 1 })
  render(<ListingsPage me={me} />)
  return { bulk, single }
}
afterEach(() => { cleanup(); vi.restoreAllMocks() })
describe('CONTROL allowlist', () => {
  it.each([false, true])('shows current state and toggles one mapped listing (enabled=%s)', async (enabled) => {
    const { single } = setup('CONTROL', owner, enabled)
    const toggle = await screen.findByRole('button', { name: 'คุมสต็อกโดย OMS' })
    expect(toggle).toHaveTextContent(enabled ? 'คุมอยู่' : 'ไม่ได้คุม')
    expect(toggle).toHaveAttribute('aria-pressed', String(enabled))
    expect(screen.getByText('คุมเฉพาะที่เลือก')).toBeVisible()
    fireEvent.click(toggle)
    await waitFor(() => expect(single).toHaveBeenCalledWith('l', !enabled))
  })
  it('confirms count, cancellation makes no request, then bulk submits', async () => {
    const { bulk } = setup()
    fireEvent.click(await screen.findByRole('checkbox', { name: 'เลือก L-demo-ready' }))
    fireEvent.click(screen.getAllByRole('button', { name: 'เปิดคุมสต็อก' }).at(-1)!)
    expect(screen.getByRole('dialog')).toHaveTextContent('1 รายการ')
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'ยกเลิก' }))
    expect(bulk).not.toHaveBeenCalled()
    fireEvent.click(screen.getAllByRole('button', { name: 'เปิดคุมสต็อก' }).at(-1)!)
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'ยืนยัน' }))
    await waitFor(() => expect(bulk).toHaveBeenCalledWith('ca', ['l'], true))
  })
  it.each([
    ['ACTIVE', owner],
    ['CONTROL', { ...owner, role: 'STAFF' } as Me],
    ['CONTROL', { ...owner, entitlement: { ...owner.entitlement, status: 'GRACE' } } as Me],
  ])('hides row and bulk writes for %s / %s', async (mode, me) => {
    setup(mode, me)
    await screen.findByText('L-demo-ready')
    expect(screen.queryByRole('checkbox')).toBeNull()
    expect(screen.queryByRole('button', { name: 'คุมสต็อกโดย OMS' })).toBeNull()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.queryByRole('button', { name: 'เปิดคุมสต็อก' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'ปิดคุมสต็อก' })).toBeNull()
  })
})
