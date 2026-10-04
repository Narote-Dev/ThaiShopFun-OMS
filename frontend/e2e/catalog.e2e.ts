import { expect, test } from '@playwright/test'

// T07: the owner of Active Shop creates a SKU, finds it through search, then imports a CSV
// with one bad row and sees that row's error. Codes are unique per run so a reused local
// database does not collide.
test('owner creates a SKU, finds it by search, and sees a CSV row error', async ({ page }) => {
  test.setTimeout(120_000)
  const code = `E2E-${Date.now()}`

  // Step 1: Sign in through the mock IdP and open the new SKU form from the shell nav.
  await page.goto('/')
  await page.getByRole('link', { name: /^Active Shop/ }).click()
  await expect(page.getByRole('heading', { name: 'Active Shop' })).toBeVisible()
  await page.getByRole('link', { name: 'SKUs', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'SKUs' })).toBeVisible()
  await page.getByRole('link', { name: 'New SKU' }).click()
  await expect(page.getByRole('heading', { name: 'New SKU' })).toBeVisible()

  // Step 2: Create the SKU with a new product in the same call.
  const productSelect = page.getByRole('main').getByRole('combobox')
  await expect
    .poll(
      async () => {
        if ((await productSelect.locator('option').count()) < 1) return false
        await productSelect.selectOption('__new__')
        return await page.getByLabel('New product name').isVisible()
      },
      { timeout: 30_000, intervals: [500] },
    )
    .toBeTruthy()
  await page.getByLabel('New product name').fill('E2E Product')
  await page.getByLabel('SKU code').fill(code)
  await page.getByLabel('Name', { exact: true }).fill('E2E red mug')
  await page.getByLabel('Barcode').fill(`885${Date.now()}`)
  await page.getByRole('button', { name: 'Create SKU' }).click()
  await expect(page.getByRole('heading', { name: code })).toBeVisible()

  // Step 3: Find it by a lower-case code prefix.
  await page.getByRole('link', { name: 'Back to SKUs' }).click()
  await page.getByLabel('Search SKUs').fill(code.toLowerCase())
  await page.getByRole('button', { name: 'Search' }).click()
  await expect(page.getByRole('link', { name: code })).toBeVisible()

  // Step 4: Import a small file whose line 3 has no sku_name. Nothing is imported.
  await page.getByRole('link', { name: 'Import', exact: true }).click()
  const csv =
    'product_name,sku_code,sku_name,barcode,weight_g,is_bundle,components\n' +
    `E2E Product,${code}-A,Good row,,100,false,\n` +
    `E2E Product,${code}-B,,,100,false,\n`
  await page.getByLabel('CSV file').setInputFiles({
    name: 'catalog.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from(csv, 'utf-8'),
  })
  await page.getByRole('button', { name: 'Import' }).click()
  await expect(page.getByRole('alert')).toContainText('nothing was imported')
  const errors = page.getByRole('table', { name: 'Row errors' })
  await expect(errors.getByRole('row')).toHaveCount(2)
  await expect(errors.getByRole('row').nth(1)).toContainText('3')
  await expect(errors.getByRole('row').nth(1)).toContainText('sku_name')

  // Step 5: The good row of the rejected file was not written either.
  await page.getByRole('link', { name: 'SKUs', exact: true }).click()
  await page.getByLabel('Search SKUs').fill(`${code}-a`)
  await page.getByRole('button', { name: 'Search' }).click()
  await expect(page.getByText('No SKUs found.')).toBeVisible()
})
