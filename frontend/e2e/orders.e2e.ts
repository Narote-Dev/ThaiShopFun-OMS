import { expect, test } from '@playwright/test'

test('owner browses orders, opens detail with masked phone, requests cancel', async ({ page, request }) => {
  test.setTimeout(180_000)
  await page.goto('/')
  await page.getByRole('link', { name: /^Active Shop/ }).click()
  await expect(page.getByRole('heading', { name: 'Active Shop' })).toBeVisible()

  const catalog = await request.post('http://127.0.0.1:8080/control/demo/order-catalog')
  expect(catalog.ok()).toBeTruthy()
  expect((await catalog.json()).status).toBe('OK')
  const seed = await request.post('http://127.0.0.1:8090/control/demo/orders-seed')
  expect(seed.ok()).toBeTruthy()

  await page.getByRole('link', { name: 'Orders', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Orders' })).toBeVisible()

  const search = page.getByLabel('Search')
  await expect
    .poll(
      async () => {
        await search.fill('DEMO-COD')
        await page.getByRole('button', { name: 'Apply' }).click()
        return await page.getByRole('link', { name: 'DEMO-COD' }).count()
      },
      { timeout: 120_000, intervals: [3000] },
    )
    .toBeGreaterThan(0)

  await page.getByRole('link', { name: 'DEMO-COD' }).click()
  await expect(page.getByRole('heading', { name: 'Order DEMO-COD' })).toBeVisible()
  await expect(page.getByText(/\*\*\*-\*\*\*-/)).toBeVisible()
  await expect(page.getByRole('list', { name: 'Status timeline' })).not.toBeEmpty()

  await page.getByRole('link', { name: '← Orders' }).click()
  await page.getByLabel('Hold').selectOption('SKU_NOT_MAPPED')
  await search.fill('')
  await page.getByRole('button', { name: 'Apply' }).click()
  await expect
    .poll(
      async () => await page.getByRole('link', { name: 'DEMO-UNMAPPED' }).count(),
      { timeout: 60_000, intervals: [2000] },
    )
    .toBeGreaterThan(0)
  await page.getByRole('link', { name: 'DEMO-UNMAPPED' }).click()
  await expect(page.getByRole('list', { name: 'Status timeline' })).not.toBeEmpty()

  await page.getByRole('link', { name: '← Orders' }).click()
  await page.getByLabel('Hold').selectOption('OUT_OF_STOCK')
  await search.fill('')
  await page.getByRole('button', { name: 'Apply' }).click()
  await expect
    .poll(
      async () => await page.getByRole('link', { name: 'DEMO-OOS' }).count(),
      { timeout: 60_000, intervals: [2000] },
    )
    .toBeGreaterThan(0)

  await page.getByRole('link', { name: 'DEMO-OOS' }).click()
  await expect(page.getByRole('heading', { name: 'Order DEMO-OOS' })).toBeVisible()

  await page.getByRole('link', { name: '← Orders' }).click()
  await page.getByLabel('Hold').selectOption('')
  await search.fill('DEMO-COD')
  await page.getByRole('button', { name: 'Apply' }).click()
  await page.getByRole('link', { name: 'DEMO-COD' }).click()
  await expect(page.getByRole('heading', { name: 'Order DEMO-COD' })).toBeVisible()

  const cancel = page.getByRole('button', { name: 'Request cancel' })
  await expect(cancel).toBeVisible({ timeout: 60_000 })
  page.once('dialog', (dialog) => void dialog.accept())
  await page.getByLabel('Reason').fill('E2E cancel')
  await cancel.click()
  await expect(page.getByRole('status')).toContainText('CHANNEL_CANCEL_PENDING', { timeout: 30_000 })
})

test('owner maps unmapped listing and order becomes ready to pick', async ({ page, request }) => {
  test.setTimeout(240_000)
  let channelAccountId = ''
  page.on('response', async (response) => {
    if (!response.url().includes('/api/v1/orders') || !response.ok()) return
    try {
      const body = await response.json()
      const id = body.items?.[0]?.channel_account_id
      if (id) channelAccountId = id
    } catch {
      // ignore non-JSON
    }
  })
  await page.goto('/')
  await page.getByRole('link', { name: /^Active Shop/ }).click()
  const catalog = await request.post('http://127.0.0.1:8080/control/demo/order-catalog')
  expect(catalog.ok()).toBeTruthy()
  const seed = await request.post('http://127.0.0.1:8090/control/demo/orders-seed')
  expect(seed.ok()).toBeTruthy()

  await page.getByRole('link', { name: 'Orders', exact: true }).click()
  await page.getByLabel('Hold').selectOption('SKU_NOT_MAPPED')
  await page.getByRole('button', { name: 'Apply' }).click()
  await expect(page.getByRole('link', { name: 'DEMO-UNMAPPED' })).toBeVisible({ timeout: 120_000 })
  await expect.poll(() => channelAccountId.length > 0, { timeout: 30_000 }).toBeTruthy()

  await page.getByRole('link', { name: 'Listings' }).click()
  await page.getByLabel('Channel account id').fill(channelAccountId)
  await page.getByRole('button', { name: 'Apply' }).click()
  await page.getByRole('button', { name: 'Sync listings' }).click()
  await expect(page.getByText('L-demo-missing')).toBeVisible({ timeout: 60_000 })

  await page.getByRole('button', { name: 'Map' }).first().click()
  await page.getByLabel('SKU search').fill('DEMO-SKU-READY')
  await page.getByRole('button', { name: /DEMO-SKU-READY/ }).click()
  await page.getByRole('button', { name: 'Save mapping' }).click()
  await expect(page.getByText(/released 1/)).toBeVisible({ timeout: 60_000 })

  await page.getByRole('link', { name: 'Orders', exact: true }).click()
  await page.getByLabel('Hold').selectOption('')
  await page.getByLabel('Search').fill('DEMO-UNMAPPED')
  await page.getByRole('button', { name: 'Apply' }).click()
  await page.getByRole('link', { name: 'DEMO-UNMAPPED' }).click()
  await expect(page.getByText('READY_TO_PICK')).toBeVisible({ timeout: 60_000 })
})
