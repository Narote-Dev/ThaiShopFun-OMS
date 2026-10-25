import { expect, test } from '@playwright/test'

const oms = process.env.OMS_BASE_URL ?? 'http://127.0.0.1:8080'
const mock = process.env.MOCK_BASE_URL ?? 'http://127.0.0.1:8090'

test('OWNER bulk allowlist immediately controls checkout and can be disabled', async ({ page, request }) => {
  test.setTimeout(180_000)
  await page.goto('/')
  await page.getByRole('link', { name: /^Active Shop/, exact: false }).first().click()
  await expect(page.getByRole('heading', { name: 'Active Shop', exact: true })).toBeVisible()
  expect((await request.post(`${oms}/control/demo/order-catalog`)).ok()).toBeTruthy()
  const fixture = await request.post(`${oms}/control/demo/stock-control-mode`, { data: { mode: 'CONTROL', reset_allowlist: true } })
  expect(fixture.ok()).toBeTruthy()
  const { channel_account_id: account } = await fixture.json()
  try {
    await page.goto(`/#/channel/listings?channel_account_id=${account}&mapped=all`)
    const toggle = page.getByRole('row').filter({ has: page.getByRole('checkbox', { name: 'เลือก L-demo-ready', exact: true }) }).getByRole('button', { name: 'คุมสต็อกโดย OMS', exact: true })
    await expect(page.getByText('คุมเฉพาะที่เลือก', { exact: true })).toBeVisible()
    await expect(toggle).toHaveText('ไม่ได้คุม')
    await expect(toggle).toHaveAttribute('aria-pressed', 'false')
    await page.getByRole('checkbox', { name: 'เลือก L-demo-ready', exact: true }).check()
    await page.getByRole('checkbox', { name: 'เลือก L-demo-cod', exact: true }).check()
    await page.getByRole('button', { name: 'เปิดคุมสต็อก', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: 'เปิดคุมสต็อก' })
    await expect(dialog).toContainText('2 รายการ')
    await page.screenshot({ path: 'test-results/stock-control-bulk-dialog.png', fullPage: false })
    await dialog.getByRole('button', { name: 'ยืนยัน', exact: true }).click()
    await expect(dialog).not.toBeVisible()
    await expect(toggle).toHaveAttribute('aria-pressed', 'true')
    await expect(toggle).toHaveText('คุมอยู่')
    await page.screenshot({ path: 'test-results/stock-control-control.png', fullPage: true })
    for (const [listing, enforced] of [['L-demo-ready', true], ['L-demo-cod', true], ['L-demo-oos', false]] as const) {
      const checkout = `t40a-${listing}-${Date.now()}`
      const response = await request.post(`${mock}/control/checkout/reservations`, { data: { checkout_id: checkout, tsf_shop_id: 'shop_active', items: [{ listing_sku_id: listing, qty: 1 }] } })
      expect(response.ok()).toBeTruthy()
      const result = await response.json()
      expect(result.oms_status).toBe(201)
      const body = JSON.parse(result.oms_body)
      expect(body.enforced).toBe(enforced)
      if (body.reservation_id) await request.delete(`${mock}/control/checkout/reservations/${body.reservation_id}`)
    }
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-pressed', 'false')
    await expect(toggle).toHaveText('ไม่ได้คุม')
  } finally {
    expect((await request.post(`${oms}/control/demo/stock-control-mode`, { data: { mode: 'ACTIVE' } })).ok()).toBeTruthy()
  }
})
