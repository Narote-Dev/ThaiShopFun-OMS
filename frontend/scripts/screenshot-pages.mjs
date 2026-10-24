/**
 * Playwright screenshot helper for PR / docs (run with stack up: npm run dev + backend).
 * Usage: node scripts/screenshot-pages.mjs [outputDir]
 *
 * Signs in once (in-memory OIDC); navigates via sidebar hash links — no full reload after auth.
 */
import { chromium, expect } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'

const base = process.env.PLAYWRIGHT_BASE_URL ?? 'http://127.0.0.1:5173'
const outDir = path.resolve(process.argv[2] ?? 'docs/ui/screenshots')

async function signInOnce(page) {
  await page.goto(base)
  const shop = page.getByRole('link', { name: /^Active Shop/ })
  await shop.click()
  await page.getByRole('heading', { name: 'Active Shop' }).waitFor({ timeout: 60_000 })
}

async function navTo(page, ariaLabel) {
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: ariaLabel }).click()
  const headings = { Dashboard: 'Active Shop', Orders: 'Orders', SKUs: 'SKUs', Products: 'Products', Import: 'Import catalog', Warehouses: 'Warehouses', 'Stock documents': 'Stock documents', Listings: 'Channel listings', 'Hold queue': 'Hold queue', 'Dead outbox': 'Dead outbox' }
  await page.getByRole('heading', { name: headings[ariaLabel], exact: true }).waitFor()
}

async function capture(page, name) {
  await page.evaluate(() => document.fonts.ready)
  await page.screenshot({ path: path.join(outDir, `${name}.png`), fullPage: true })
}

await mkdir(outDir, { recursive: true })
const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })

await page.goto(base)
await capture(page, 'login')

await signInOnce(page)
await navTo(page, 'Dashboard')
await capture(page, 'dashboard')

await navTo(page, 'Orders')
await capture(page, 'orders')

await page.getByRole('link', { name: 'DEMO-UNMAPPED' }).click()
await page.getByRole('heading', { name: 'Order DEMO-UNMAPPED' }).waitFor({ timeout: 60_000 })
await capture(page, 'order-detail-demo')

await navTo(page, 'Orders')
await page.getByRole('link', { name: 'DEMO-COD', exact: true }).click()
await page.getByRole('heading', { name: 'Order DEMO-COD' }).waitFor()
await page.getByRole('button', { name: 'ขอยกเลิก', exact: true }).click()
const cancelDialog = page.getByRole('dialog', { name: 'ขอยกเลิกออเดอร์' })
await cancelDialog.getByLabel('เหตุผล', { exact: true }).selectOption('ลูกค้าขอยกเลิก')
await capture(page, 'cancel-request-dialog')
await cancelDialog.getByRole('button', { name: 'ส่งคำขอยกเลิก' }).click()
await page.getByText('รอ TSF ตัดสินคำขอยกเลิก', { exact: true }).waitFor()
await capture(page, 'cancel-pending-banner')
await navTo(page, 'Orders')
await page.getByRole('button', { name: /^รอยกเลิก/ }).click()
await page.getByRole('link', { name: 'DEMO-COD', exact: true }).waitFor()
await expect(page.getByRole('button', { name: /^รอยกเลิก/ })).toContainText(/\d/)
await capture(page, 'orders-cancel-pending-tab')

// Capture the same cancelled order before and after the local/e2e-only control.
await navTo(page, 'Orders')
await page.getByRole('link', { name: 'DEMO-CANCELLED' }).click()
await page.getByRole('heading', { name: 'Order DEMO-CANCELLED' }).waitFor()
await capture(page, 'order-detail-before-redaction')
const redaction = await page.request.post(`${process.env.OMS_BASE_URL ?? 'http://127.0.0.1:8080'}/control/demo/pii-redact-cancelled`)
if (!redaction.ok()) throw new Error(`Demo redaction failed: ${redaction.status()}`)
await navTo(page, 'Orders')
await page.getByRole('link', { name: 'DEMO-CANCELLED' }).click()
await page.getByText('ข้อมูลผู้รับถูกลบตามนโยบาย PDPA แล้ว (เหลือจังหวัด/รหัสไปรษณีย์)').waitFor()
await capture(page, 'order-detail-redacted')

await navTo(page, 'SKUs')
await capture(page, 'skus')

await navTo(page, 'Products')
await capture(page, 'products')

await navTo(page, 'Import')
await capture(page, 'import')

await navTo(page, 'Warehouses')
await capture(page, 'warehouses')

await navTo(page, 'Stock documents')
await capture(page, 'stock-documents')

await page.getByLabel('New document type').selectOption('RECEIVE')
await page.getByRole('form', { name: 'New stock document' }).getByLabel('Reference no.').fill('UI-CANCEL-SCREENSHOT')
await page.getByRole('button', { name: 'Create document' }).click()
await page.getByRole('heading', { name: 'Receive · DRAFT' }).waitFor()
const addLine = page.getByRole('form', { name: 'Add line' })
await addLine.getByLabel('SKU code').fill('DEMO-SKU-READY')
await addLine.getByLabel('Qty').fill('1')
await addLine.getByRole('button', { name: 'Add line' }).click()
await page.getByRole('table', { name: 'Lines' }).getByText('DEMO-SKU-READY', { exact: true }).waitFor()
await page.getByRole('button', { name: 'Post', exact: true }).click()
await page.getByRole('dialog', { name: 'ลงบัญชีเอกสารสต็อก' }).waitFor()
await capture(page, 'stock-post-dialog')
await page.getByRole('dialog').getByRole('button', { name: 'ยกเลิก', exact: true }).click()

await navTo(page, 'Listings')
await capture(page, 'listings')
const syncBtn = page.getByRole('button', { name: 'Sync listings' })
if (await syncBtn.isVisible().catch(() => false)) {
  await syncBtn.click()
  await page.getByLabel('ผลการซิงก์ listings').waitFor({ timeout: 60_000 }).catch(() => {})
  await capture(page, 'listings-sync-result')
}
await page.getByRole('button', { name: 'ถูกลบจาก TSF' }).click().catch(() => {})
await page.getByRole('button', { name: 'ถูกลบจาก TSF' }).waitFor()
await capture(page, 'listings-removed-filter')

await navTo(page, 'Hold queue')
await capture(page, 'hold-queue')

await navTo(page, 'Dead outbox')
await capture(page, 'outbox')

await browser.close()
console.log(`Wrote screenshots to ${outDir}`)
