import { expect, test } from '@playwright/test'
import { searchSkus } from './catalog-support'

// T07: the owner of Active Shop creates a SKU, finds it through search, then imports a CSV
// with one bad row and sees that row's error. Codes are unique per run so a reused local
// database does not collide.
test('owner creates a SKU, finds it by search, and sees a CSV row error', async ({ page }) => {
  test.setTimeout(120_000)
  const code = `E2E-${Date.now()}`

  // Step 1: Sign in through the mock IdP and open the new SKU form from the shell nav.
  await page.goto('/')
  await page.getByRole('link', { name: /^Active Shop/ }).click()
  await expect(page.getByRole('heading', { name: 'Active Shop', exact: true })).toBeVisible()
  await page.getByRole('navigation', { name: 'เมนูหลัก', exact: true }).getByRole('link', { name: 'รหัสสินค้า', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'รหัสสินค้า', exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'เพิ่มรหัสสินค้า', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'เพิ่มรหัสสินค้า', exact: true })).toBeVisible()

  // Step 2: Create the SKU with a new product in the same call.
  const productSelect = page.getByRole('main').getByLabel('สินค้า', { exact: true })
  await expect(productSelect).toBeEnabled()
  await productSelect.selectOption('__new__')
  await expect(page.getByRole('main').getByLabel('ชื่อสินค้าใหม่', { exact: true })).toBeVisible()
  await page.getByRole('main').getByLabel('ชื่อสินค้าใหม่', { exact: true }).fill('E2E Product')
  await page.getByRole('main').getByLabel('รหัสสินค้า', { exact: true }).fill(code)
  await page.getByRole('main').getByLabel('ชื่อ', { exact: true }).fill('E2E red mug')
  await page.getByRole('main').getByLabel('บาร์โค้ด', { exact: true }).fill(`885${Date.now()}`)
  await page.getByRole('button', { name: 'สร้างรหัสสินค้า', exact: true }).click()
  await expect(page.getByRole('heading', { name: code })).toBeVisible()

  // Step 3: Find it by a lower-case code prefix.
  await page.getByRole('link', { name: 'กลับไปรายการรหัสสินค้า', exact: true }).click()
  await searchSkus(page, code.toLowerCase())
  await expect(page.getByRole('link', { name: code })).toBeVisible()

  // Step 4: Import a small file whose line 3 has no sku_name. Nothing is imported.
  await page.getByRole('navigation', { name: 'เมนูหลัก', exact: true }).getByRole('link', { name: 'นำเข้าข้อมูล', exact: true }).click()
  const csv =
    'product_name,sku_code,sku_name,barcode,weight_g,is_bundle,components\n' +
    `E2E Product,${code}-A,Good row,,100,false,\n` +
    `E2E Product,${code}-B,,,100,false,\n`
  await page.getByRole('main').getByLabel('ไฟล์ซีเอสวี', { exact: true }).setInputFiles({
    name: 'catalog.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from(csv, 'utf-8'),
  })
  await page.getByRole('button', { name: 'นำเข้าข้อมูล', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('ข้อมูลในไฟล์ไม่ถูกต้อง ยังไม่ได้นำเข้าข้อมูล')
  const errors = page.getByRole('table', { name: 'ข้อผิดพลาดรายแถว', exact: true })
  await expect(errors.getByRole('row')).toHaveCount(2)
  await expect(errors.getByRole('row').nth(1)).toContainText('3')
  await expect(errors.getByRole('row').nth(1)).toContainText('ชื่อรหัสสินค้า')

  // Step 5: The good row of the rejected file was not written either.
  await page.getByRole('navigation', { name: 'เมนูหลัก', exact: true }).getByRole('link', { name: 'รหัสสินค้า', exact: true }).click()
  await searchSkus(page, `${code}-a`)
  await expect(page.getByText('ไม่พบรหัสสินค้า')).toBeVisible()
})
