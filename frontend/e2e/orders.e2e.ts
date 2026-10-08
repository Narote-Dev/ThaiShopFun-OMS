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

test('owner sync listings shows removed zero for demo catalog', async ({ page, request }) => {
  test.setTimeout(180_000)
  const token = await ownerToken(request)
  await page.goto('/')
  await page.getByRole('link', { name: /^Active Shop/ }).click()
  const seed = await request.post('http://127.0.0.1:8090/control/demo/orders-seed')
  expect(seed.ok()).toBeTruthy()
  const catalog = await request.post('http://127.0.0.1:8080/control/demo/order-catalog')
  expect(catalog.ok()).toBeTruthy()
  const channelAccountId = (await catalog.json()).channel_account_id as string
  await page.goto(`/#/channel/listings?channel_account_id=${channelAccountId}`)
  await page.getByRole('button', { name: 'Sync listings' }).click()
  await expect(page.getByText(/ถูกลบจาก TSF 0/)).toBeVisible({ timeout: 90_000 })
})

test('owner maps unmapped listing via listings UI and order becomes ready to pick', async ({
  page,
  request,
}) => {
  test.setTimeout(300_000)
  const token = await ownerToken(request)
  await page.goto('/')
  await page.getByRole('link', { name: /^Active Shop/ }).click()

  const seed = await request.post('http://127.0.0.1:8090/control/demo/orders-seed')
  expect(seed.ok()).toBeTruthy()

  let channelAccountId = ''
  await expect
    .poll(
      async () => {
        const catalog = await request.post('http://127.0.0.1:8080/control/demo/order-catalog')
        if (!catalog.ok()) return 0
        const ordersResponse = await request.get(
          'http://127.0.0.1:8080/api/v1/orders?hold_reason=SKU_NOT_MAPPED&limit=20',
          { headers: { Authorization: `Bearer ${token}` } },
        )
        if (!ordersResponse.ok()) return 0
        const held = (await ordersResponse.json()).items.find(
          (row: { external_order_id: string }) => row.external_order_id === 'DEMO-UNMAPPED',
        ) as { channel_account_id: string } | undefined
        if (!held) return 0
        channelAccountId = held.channel_account_id
        const body = await catalog.json()
        return body.L_demo_missing_listing_id ? 1 : 0
      },
      { timeout: 180_000, intervals: [3000] },
    )
    .toBeGreaterThan(0)

  await page.goto(
    `/#/channel/listings?channel_account_id=${channelAccountId}&mapped=false&q=L-demo-missing`,
  )
  await expect(page.getByRole('heading', { name: 'Channel listings' })).toBeVisible()
  await expect(page.getByText('L-demo-missing')).toBeVisible({ timeout: 60_000 })
  await page.getByRole('button', { name: 'Map' }).first().click()
  await page.getByLabel('SKU search').fill('DEMO-SKU-READY')
  await expect(page.getByRole('button', { name: /DEMO-SKU-READY/ })).toBeVisible({
    timeout: 30_000,
  })
  await page.getByRole('button', { name: /DEMO-SKU-READY/ }).click()
  await page.getByRole('button', { name: 'Save mapping' }).click()
  await expect(page.getByText(/released 1/)).toBeVisible({ timeout: 90_000 })

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Orders' }).click()
  await page.getByLabel('Search').fill('DEMO-UNMAPPED')
  await page.getByRole('button', { name: 'Apply' }).click()
  await page.getByRole('link', { name: 'DEMO-UNMAPPED' }).click()
  await expect(page.getByRole('heading', { name: 'Order DEMO-UNMAPPED' })).toBeVisible()
  await expect(page.locator('dt:has-text("Fulfillment") + dd')).toHaveText('READY_TO_PICK', {
    timeout: 90_000,
  })
})
