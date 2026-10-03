import { expect, test } from '@playwright/test'

test('owner browses orders, opens detail with masked phone, requests cancel', async ({ page, request }) => {
  await page.goto('/')
  await page.getByRole('link', { name: /^Active Shop/ }).click()
  await expect(page.getByRole('heading', { name: 'Active Shop' })).toBeVisible()

  await request.post('http://127.0.0.1:8090/control/demo/orders-seed')

  await page.getByRole('link', { name: 'Orders', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Orders' })).toBeVisible()

  await page.getByLabel('Search').fill('DEMO-COD')
  await page.getByRole('button', { name: 'Apply' }).click()
  const codLink = page.getByRole('link', { name: 'DEMO-COD' })
  await expect(codLink).toBeVisible({ timeout: 120_000 })
  await codLink.click()
  await expect(page.getByRole('heading', { name: 'Order DEMO-COD' })).toBeVisible()
  await expect(page.getByText(/\*\*\*-\*\*\*-/)).toBeVisible()

  const cancel = page.getByRole('button', { name: 'Request cancel' })
  await expect(cancel).toBeVisible()
  page.once('dialog', (dialog) => void dialog.accept())
  await page.getByLabel('Reason').fill('E2E cancel')
  await cancel.click()
  await expect(page.getByText('CHANNEL_CANCEL_PENDING')).toBeVisible({ timeout: 30_000 })
})
