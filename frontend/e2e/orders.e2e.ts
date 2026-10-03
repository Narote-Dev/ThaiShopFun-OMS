import { expect, test } from '@playwright/test'

async function ownerToken(request: import('@playwright/test').APIRequestContext) {
  const response = await request.post('http://127.0.0.1:8090/control/user-token', {
    headers: { 'Content-Type': 'application/json' },
    data: { login_hint: 'owner-active' },
  })
  expect(response.ok()).toBeTruthy()
  return (await response.json()).access_token as string
}

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
  await page.getByLabel('Hold filter').selectOption('SKU_NOT_MAPPED')
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
  await page.getByLabel('Hold filter').selectOption('OUT_OF_STOCK')
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
  await page.getByLabel('Hold filter').selectOption('')
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
  const token = await ownerToken(request)
  const me = await request.get('http://127.0.0.1:8080/api/v1/me', {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(me.ok()).toBeTruthy()
  const seed = await request.post('http://127.0.0.1:8090/control/demo/orders-seed')
  expect(seed.ok()).toBeTruthy()
  const catalog = await request.post('http://127.0.0.1:8080/control/demo/order-catalog')
  expect(catalog.ok()).toBeTruthy()

  const ordersResponse = await request.get(
    'http://127.0.0.1:8080/api/v1/orders?hold_reason=SKU_NOT_MAPPED&limit=10',
    { headers: { Authorization: `Bearer ${token}` } },
  )
  expect(ordersResponse.ok()).toBeTruthy()
  const heldOrder = (await ordersResponse.json()).items.find(
    (row: { external_order_id: string }) => row.external_order_id === 'DEMO-UNMAPPED',
  )
  expect(heldOrder).toBeTruthy()

  const skuResponse = await request.get(
    'http://127.0.0.1:8080/api/v1/skus?q=DEMO-SKU-READY&limit=10',
    { headers: { Authorization: `Bearer ${token}` } },
  )
  expect(skuResponse.ok()).toBeTruthy()
  const readySku = (await skuResponse.json()).items.find(
    (row: { sku_code: string }) => row.sku_code === 'DEMO-SKU-READY',
  )
  expect(readySku).toBeTruthy()

  const catalogBody = await catalog.json()
  const listingId = catalogBody.L_demo_missing_listing_id as string
  expect(listingId.length).toBeGreaterThan(0)

  const mapResponse = await request.put(
    `http://127.0.0.1:8080/api/v1/channel-listings/${listingId}/mapping`,
    {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: { sku_id: readySku.id },
    },
  )
  if (!mapResponse.ok()) {
    throw new Error(`mapping failed: ${mapResponse.status()} ${await mapResponse.text()}`)
  }
  expect((await mapResponse.json()).reevaluation.released).toBeGreaterThanOrEqual(1)

  await page.goto('/')
  await page.getByRole('link', { name: /^Active Shop/ }).click()
  await page.getByRole('link', { name: 'Orders', exact: true }).click()
  await page.getByLabel('Search').fill('DEMO-UNMAPPED')
  await page.getByRole('button', { name: 'Apply' }).click()
  await page.getByRole('link', { name: 'DEMO-UNMAPPED' }).click()
  await expect(page.getByText('READY_TO_PICK')).toBeVisible({ timeout: 60_000 })
})
