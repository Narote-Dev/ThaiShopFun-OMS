import { expect, test } from '@playwright/test'

test('owner browses orders, opens detail with masked phone, requests cancel', async ({ page, request }) => {
  await page.goto('/')
  await page.getByRole('link', { name: /^Active Shop/ }).click()
  await expect(page.getByRole('heading', { name: 'Active Shop' })).toBeVisible()

  // Step 1: Seed demo orders after the shop is provisioned (idempotent).
  await request.post('http://127.0.0.1:8090/control/demo/orders-seed')

  await page.getByRole('link', { name: 'Orders', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Orders' })).toBeVisible()

  const firstOrder = page.getByRole('table', { name: 'Orders' }).getByRole('link').first()
  await expect(firstOrder).toBeVisible({ timeout: 120_000 })
  const label = await firstOrder.textContent()
  await firstOrder.click()
  await expect(page.getByRole('heading', { name: `Order ${label}` })).toBeVisible()
  await expect(page.getByText(/\*\*\*-\*\*\*-/)).toBeVisible()

  const cancel = page.getByRole('button', { name: 'Request cancel' })
  if (await cancel.isVisible()) {
    page.once('dialog', (dialog) => void dialog.accept())
    await page.getByLabel('Reason').fill('E2E cancel')
    await cancel.click()
    await expect(page.getByText('CHANNEL_CANCEL_PENDING')).toBeVisible({ timeout: 30_000 })
  }
})
