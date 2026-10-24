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
  await expect(page.getByRole('heading', { name: 'Active Shop', exact: true })).toBeVisible()

  const catalog = await request.post('http://127.0.0.1:8080/control/demo/order-catalog')
  expect(catalog.ok()).toBeTruthy()
  expect((await catalog.json()).status).toBe('OK')
  const seed = await request.post('http://127.0.0.1:8090/control/demo/orders-seed')
  expect(seed.ok()).toBeTruthy()

  await page.getByRole('navigation', { name: 'เมนูหลัก', exact: true }).getByRole('link', { name: 'ออเดอร์', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'ออเดอร์', exact: true })).toBeVisible()

  const search = page.getByRole('main').getByLabel('ค้นหา', { exact: true })
  await expect
    .poll(
      async () => {
        await search.fill('DEMO-COD')
        await page.getByRole('button', { name: 'ใช้ตัวกรอง', exact: true }).click()
        return await page.getByRole('link', { name: 'DEMO-COD', exact: true }).count()
      },
      { timeout: 120_000, intervals: [3000] },
    )
    .toBeGreaterThan(0)

  await page.getByRole('link', { name: 'DEMO-COD', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'ออเดอร์ DEMO-COD', exact: true })).toBeVisible()
  await expect(page.getByText(/\*\*\*-\*\*\*-/)).toBeVisible()
  await expect(page.getByRole('list', { name: 'ประวัติการเปลี่ยนสถานะ', exact: true })).not.toBeEmpty()

  await page.getByRole('link', { name: '← ออเดอร์', exact: true }).click()
  await page.getByRole('main').getByLabel('ตัวกรองการพักออเดอร์', { exact: true }).selectOption('SKU_NOT_MAPPED')
  await search.fill('')
  await page.getByRole('button', { name: 'ใช้ตัวกรอง', exact: true }).click()
  await expect
    .poll(
      async () => await page.getByRole('link', { name: 'DEMO-UNMAPPED', exact: true }).count(),
      { timeout: 60_000, intervals: [2000] },
    )
    .toBeGreaterThan(0)
  await page.getByRole('link', { name: 'DEMO-UNMAPPED', exact: true }).click()
  await expect(page.getByRole('list', { name: 'ประวัติการเปลี่ยนสถานะ', exact: true })).not.toBeEmpty()

  await page.getByRole('link', { name: '← ออเดอร์', exact: true }).click()
  await page.getByRole('main').getByLabel('ตัวกรองการพักออเดอร์', { exact: true }).selectOption('สต็อกไม่พอ')
  await search.fill('')
  await page.getByRole('button', { name: 'ใช้ตัวกรอง', exact: true }).click()
  await expect
    .poll(
      async () => await page.getByRole('link', { name: 'DEMO-OOS', exact: true }).count(),
      { timeout: 60_000, intervals: [2000] },
    )
    .toBeGreaterThan(0)

  await page.getByRole('link', { name: 'DEMO-OOS', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'ออเดอร์ DEMO-OOS', exact: true })).toBeVisible()

  await page.getByRole('link', { name: '← ออเดอร์', exact: true }).click()
  await page.getByRole('main').getByLabel('ตัวกรองการพักออเดอร์', { exact: true }).selectOption('')
  await search.fill('DEMO-COD')
  await page.getByRole('button', { name: 'ใช้ตัวกรอง', exact: true }).click()
  await page.getByRole('link', { name: 'DEMO-COD', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'ออเดอร์ DEMO-COD', exact: true })).toBeVisible()

  const cancel = page.getByRole('button', { name: 'ขอยกเลิก', exact: true })
  await expect(cancel).toBeVisible({ timeout: 60_000 })
  await cancel.click()
  const dialog = page.getByRole('dialog', { name: 'ขอยกเลิกออเดอร์', exact: true })
  await dialog.getByLabel('เหตุผล', { exact: true }).selectOption('อื่นๆ')
  await dialog.getByLabel('เหตุผลอื่นๆ', { exact: true }).fill('E2E cancel')
  await dialog.getByRole('button', { name: 'ส่งคำขอยกเลิก', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('รอยกเลิกจากช่องทาง', { timeout: 30_000 })
  await expect(page.getByText('รอ TSF ตัดสินคำขอยกเลิก', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'ส่งคำขอแล้ว', exact: true })).toBeDisabled()
  await page.getByRole('link', { name: '← ออเดอร์', exact: true }).click()
  await page.getByRole('button', { name: /^รอยกเลิก/ }).click()
  await expect(page.getByRole('link', { name: 'DEMO-COD', exact: true })).toBeVisible()

})

test('owner sync listings shows removed zero for demo catalog', async ({ page, request }) => {
  test.setTimeout(180_000)
  await page.goto('/')
  await page.getByRole('link', { name: /^Active Shop/ }).click()
  await expect(page.getByRole('heading', { name: 'Active Shop', exact: true })).toBeVisible()
  const seed = await request.post('http://127.0.0.1:8090/control/demo/orders-seed')
  expect(seed.ok()).toBeTruthy()
  const catalog = await request.post('http://127.0.0.1:8080/control/demo/order-catalog')
  expect(catalog.ok()).toBeTruthy()
  const channelAccountId = (await catalog.json()).channel_account_id as string
  await page.goto(`/#/channel/listings?channel_account_id=${channelAccountId}`)
  await page.getByRole('button', { name: 'ซิงก์รายการขาย', exact: true }).click()
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
  await expect(page.getByRole('heading', { name: 'Active Shop', exact: true })).toBeVisible()

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

  // Stay in the SPA session (full page.goto drops OIDC state and stalls on authorize).
  await page.evaluate(
    ({ channelAccountId }) => {
      window.location.hash = `/channel/listings?channel_account_id=${encodeURIComponent(channelAccountId)}&mapped=false&q=L-demo-missing`
    },
    { channelAccountId },
  )
  await expect(page.getByRole('heading', { name: 'รายการขายจากช่องทาง', exact: true })).toBeVisible()
  await expect(page.getByText('L-demo-missing')).toBeVisible({ timeout: 60_000 })
  await page.getByRole('row').filter({ has: page.getByText('L-demo-missing', { exact: true }) }).getByRole('button', { name: 'จับคู่', exact: true }).click()
  await page.getByRole('dialog', { name: 'จับคู่ L-demo-missing', exact: true }).getByLabel('ค้นหารหัสสินค้า', { exact: true }).fill('DEMO-SKU-READY')
  await expect(page.getByRole('button', { name: /DEMO-SKU-READY/ })).toBeVisible({
    timeout: 30_000,
  })
  await page.getByRole('button', { name: /DEMO-SKU-READY/ }).click()
  await page.getByRole('button', { name: 'บันทึกการจับคู่', exact: true }).click()
  await expect(page.getByText(/ปล่อยออเดอร์แล้ว 1/)).toBeVisible({ timeout: 90_000 })

  await page.getByRole('navigation', { name: 'เมนูหลัก', exact: true }).getByRole('link', { name: 'ออเดอร์', exact: true }).click()
  await page.getByRole('main').getByLabel('ค้นหา', { exact: true }).fill('DEMO-UNMAPPED')
  await page.getByRole('button', { name: 'ใช้ตัวกรอง', exact: true }).click()
  await page.getByRole('link', { name: 'DEMO-UNMAPPED', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'ออเดอร์ DEMO-UNMAPPED', exact: true })).toBeVisible()
  await expect(page.locator('dt:has-text("การจัดส่ง") + dd')).toHaveText('รอหยิบ', {
    timeout: 90_000,
  })
})
