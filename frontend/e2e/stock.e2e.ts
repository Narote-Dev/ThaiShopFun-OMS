import { expect, test } from '@playwright/test'
import { searchSkus } from './catalog-support'

// T08A: the owner of Active Shop creates a SKU, receives 5 units through a RECEIVE document,
// posts it, and finds the RECEIVE entry in that SKU's stock history with a link back to the
// document. Codes are unique per run so a reused local database does not collide.
test('owner posts a RECEIVE and sees it in the SKU stock history', async ({ page }) => {
  const code = `E2E-STK-${Date.now()}`
  const reference = `PO-${Date.now()}`
  const product = `E2E Stock ${Date.now()}`

  // Step 1: Sign in through the mock IdP, add a product, and create a plain SKU under it.
  await page.goto('/')
  await page.getByRole('link', { name: /^Active Shop/ }).click()
  await expect(page.getByRole('heading', { name: 'Active Shop', exact: true })).toBeVisible()
  await page.getByRole('navigation', { name: 'เมนูหลัก', exact: true }).getByRole('link', { name: 'สินค้า', exact: true }).click()
  await page.getByRole('main').getByLabel('ชื่อสินค้าใหม่', { exact: true }).fill(product)
  await page.getByRole('button', { name: 'เพิ่มสินค้า', exact: true }).click()
  await expect(page.getByRole('main').getByLabel(`ชื่อของ ${product}`, { exact: true })).toBeVisible()
  await page.getByRole('navigation', { name: 'เมนูหลัก', exact: true }).getByRole('link', { name: 'รหัสสินค้า', exact: true }).click()
  await page.getByRole('link', { name: 'เพิ่มรหัสสินค้า', exact: true }).click()
  const productSelect = page.getByRole('main').getByLabel('สินค้า', { exact: true })
  await expect(productSelect).toBeEnabled()
  await productSelect.selectOption({ label: product })
  await expect(productSelect.locator('option:checked')).toHaveText(product)
  await page.getByRole('main').getByLabel('รหัสสินค้า', { exact: true }).fill(code)
  await page.getByRole('main').getByLabel('ชื่อ', { exact: true }).fill('E2E stock item')
  await page.getByRole('button', { name: 'สร้างรหัสสินค้า', exact: true }).click()
  await expect(page.getByRole('heading', { name: code })).toBeVisible()

  // Step 2: New RECEIVE draft from the Stock documents page.
  await page.getByRole('navigation', { name: 'เมนูหลัก', exact: true }).getByRole('link', { name: 'เอกสารสต็อก', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'เอกสารสต็อก', exact: true })).toBeVisible()
  await page.getByRole('main').getByLabel('ประเภทเอกสารใหม่', { exact: true }).selectOption('RECEIVE')
  await page.getByRole('form', { name: 'เอกสารสต็อกใหม่', exact: true }).getByLabel('เลขที่อ้างอิง', { exact: true }).fill(reference)
  await page.getByRole('button', { name: 'สร้างเอกสาร', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'รับเข้า · ฉบับร่าง', exact: true })).toBeVisible()

  // Step 3: One line of 5 units, then post after the confirmation dialog.
  const add = page.getByRole('form', { name: 'เพิ่มรายการ', exact: true })
  await add.getByLabel('รหัสสินค้า', { exact: true }).fill(code)
  await add.getByLabel('จำนวน', { exact: true }).fill('5')
  await add.getByRole('button', { name: 'เพิ่มรายการ', exact: true }).click()
  await expect(page.getByRole('table', { name: 'รายการ', exact: true })).toContainText(code)
  await page.getByRole('button', { name: 'ลงบัญชี', exact: true }).click()
  await page.getByRole('dialog', { name: 'ลงบัญชีเอกสารสต็อก', exact: true }).getByRole('button', { name: 'ยืนยัน', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'รับเข้า · ลงบัญชีแล้ว', exact: true })).toBeVisible()

  // Step 4: The SKU's history shows the RECEIVE with a link back to the document.
  await page.getByRole('navigation', { name: 'เมนูหลัก', exact: true }).getByRole('link', { name: 'รหัสสินค้า', exact: true }).click()
  await searchSkus(page, code.toLowerCase())
  await page.getByRole('row', { name: new RegExp(code) }).getByRole('link', { name: 'ประวัติ', exact: true }).click()
  await expect(page.getByRole('heading', { name: `ประวัติสต็อก · ${code}`, exact: true })).toBeVisible()
  const movements = page.getByRole('table', { name: 'ความเคลื่อนไหวสต็อก', exact: true })
  await expect(movements.getByRole('row')).toHaveCount(2)
  await expect(movements.getByRole('row').nth(1)).toContainText('รับเข้า')
  await expect(movements.getByRole('row').nth(1)).toContainText('+5')
  await movements.getByRole('link', { name: `รับเข้า ${reference}`, exact: true }).click()
  await expect(page.getByRole('heading', { name: 'รับเข้า · ลงบัญชีแล้ว', exact: true })).toBeVisible()
})
