import { expect, test } from '@playwright/test'

// T08A: the owner of Active Shop creates a SKU, receives 5 units through a RECEIVE document,
// posts it, and finds the RECEIVE entry in that SKU's stock history with a link back to the
// document. Codes are unique per run so a reused local database does not collide.
test('owner posts a RECEIVE and sees it in the SKU stock history', async ({ page }) => {
  const code = `E2E-STK-${Date.now()}`
  const reference = `PO-${Date.now()}`

  // Step 1: Sign in through the mock IdP and create a plain SKU.
  await page.goto('/')
  await page.getByRole('link', { name: /^Active Shop/ }).click()
  await expect(page.getByRole('heading', { name: 'Active Shop' })).toBeVisible()
  await page.getByRole('link', { name: 'SKUs', exact: true }).click()
  await page.getByRole('link', { name: 'New SKU' }).click()
  await page.getByRole('combobox').selectOption({ label: 'New product…' })
  await page.getByLabel('New product name').fill('E2E Stock Product')
  await page.getByLabel('SKU code').fill(code)
  await page.getByLabel('Name', { exact: true }).fill('E2E stock item')
  await page.getByRole('button', { name: 'Create SKU' }).click()
  await expect(page.getByRole('heading', { name: code })).toBeVisible()

  // Step 2: New RECEIVE draft from the Stock documents page.
  await page.getByRole('link', { name: 'Stock documents', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Stock documents' })).toBeVisible()
  await page.getByLabel('New document type').selectOption('RECEIVE')
  await page.getByRole('form', { name: 'New stock document' }).getByLabel('Reference no.').fill(reference)
  await page.getByRole('button', { name: 'Create document' }).click()
  await expect(page.getByRole('heading', { name: 'Receive · DRAFT' })).toBeVisible()

  // Step 3: One line of 5 units, then post after the confirmation dialog.
  const add = page.getByRole('form', { name: 'Add line' })
  await add.getByLabel('SKU code').fill(code)
  await add.getByLabel('Qty').fill('5')
  await add.getByRole('button', { name: 'Add line' }).click()
  await expect(page.getByRole('table', { name: 'Lines' })).toContainText(code)
  page.once('dialog', (dialog) => void dialog.accept())
  await page.getByRole('button', { name: 'Post' }).click()
  await expect(page.getByRole('heading', { name: 'Receive · POSTED' })).toBeVisible()

  // Step 4: The SKU's history shows the RECEIVE with a link back to the document.
  await page.getByRole('link', { name: 'SKUs', exact: true }).click()
  await page.getByLabel('Search SKUs').fill(code.toLowerCase())
  await page.getByRole('button', { name: 'Search' }).click()
  await page.getByRole('link', { name: `Stock history of ${code}` }).click()
  await expect(page.getByRole('heading', { name: `Stock history · ${code}` })).toBeVisible()
  const movements = page.getByRole('table', { name: 'Stock movements' })
  await expect(movements.getByRole('row')).toHaveCount(2)
  await expect(movements.getByRole('row').nth(1)).toContainText('RECEIVE')
  await expect(movements.getByRole('row').nth(1)).toContainText('+5')
  await movements.getByRole('link', { name: `Receive ${reference}` }).click()
  await expect(page.getByRole('heading', { name: 'Receive · POSTED' })).toBeVisible()
})
