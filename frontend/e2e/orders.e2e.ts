import { expect, test } from '@playwright/test'

test('owner browses orders, opens detail with masked phone, requests cancel', async ({ page, request }) => {
  test.setTimeout(180_000)
  await page.goto('/')
  await page.getByRole('link', { name: /^Active Shop/ }).click()
  await expect(page.getByRole('heading', { name: 'Active Shop' })).toBeVisible()

  await request.post('http://127.0.0.1:8080/control/demo/order-catalog')
  await request.post('http://127.0.0.1:8090/control/demo/orders-seed')

  await page.getByRole('link', { name: 'Orders', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Orders' })).toBeVisible()

  await expect
    .poll(
      async () => {
        await page.getByLabel('Search').fill('DEMO-COD')
        await page.getByRole('button', { name: 'Apply' }).click()
        return await page.getByRole('link', { name: 'DEMO-COD' }).count()
      },
      { timeout: 120_000, intervals: [2000] },
    )
    .toBeGreaterThan(0)
  const codLink = page.getByRole('link', { name: 'DEMO-COD' })
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
