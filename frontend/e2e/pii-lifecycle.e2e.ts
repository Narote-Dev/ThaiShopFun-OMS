import { expect, test } from '@playwright/test'

test('cancelled demo recipient becomes a PDPA card through the local control', async ({ page, request }) => {
  test.setTimeout(180_000)
  await page.goto('/')
  await page.getByRole('link', { name: /^Active Shop/ }).click()
  await expect(page.getByRole('heading', { name: 'Active Shop', exact: true })).toBeVisible()
  expect((await request.post('http://127.0.0.1:8080/control/demo/order-catalog')).ok()).toBeTruthy()
  expect((await request.post('http://127.0.0.1:8090/control/demo/orders-seed')).ok()).toBeTruthy()
  await page.getByRole('navigation', { name: 'เมนูหลัก', exact: true }).getByRole('link', { name: 'ออเดอร์', exact: true }).click()
  await expect.poll(async () => {
    await page.getByRole('main').getByLabel('ค้นหา', { exact: true }).fill('DEMO-CANCELLED')
    await page.getByRole('button', { name: 'ใช้ตัวกรอง', exact: true }).click()
    return page.getByRole('link', { name: 'DEMO-CANCELLED', exact: true }).count()
  }, { timeout: 120_000, intervals: [2000] }).toBeGreaterThan(0)
  await page.getByRole('link', { name: 'DEMO-CANCELLED', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'ออเดอร์ DEMO-CANCELLED', exact: true })).toBeVisible()
  const tokenResponse = await request.post('http://127.0.0.1:8090/control/user-token', { data: { login_hint: 'owner-active' } })
  expect(tokenResponse.ok()).toBeTruthy()
  const token = (await tokenResponse.json()).access_token as string
  const orderId = page.url().split('#/orders/')[1]
  await expect.poll(async () => {
    const detail = await request.get(`http://127.0.0.1:8080/api/v1/orders/${orderId}`, { headers: { Authorization: `Bearer ${token}` } })
    return detail.ok() ? (await detail.json()).order_status : ''
  }, { timeout: 60_000 }).toBe('CANCELLED')
  await page.getByRole('link', { name: '← ออเดอร์', exact: true }).click()
  await page.getByRole('link', { name: 'DEMO-CANCELLED', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'ออเดอร์ DEMO-CANCELLED', exact: true })).toBeVisible()
  await page.screenshot({ path: 'test-results/pii-before.png', fullPage: true })
  expect((await request.post('http://127.0.0.1:8080/control/demo/pii-redact-cancelled')).ok()).toBeTruthy()
  await page.getByRole('link', { name: '← ออเดอร์', exact: true }).click()
  await page.getByRole('link', { name: 'DEMO-CANCELLED', exact: true }).click()
  await expect(page.getByText('ข้อมูลผู้รับถูกลบตามนโยบายคุ้มครองข้อมูลส่วนบุคคล แล้ว (เหลือจังหวัด/รหัสไปรษณีย์)')).toBeVisible()
  await expect(page.getByText(/\*\*\*-\*\*\*-/)).toHaveCount(0)
  await page.screenshot({ path: 'test-results/pii-redacted.png', fullPage: true })
})
