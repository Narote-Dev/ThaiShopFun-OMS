/**
 * Playwright screenshot helper for PR / docs (run with stack up: npm run dev + backend).
 * Usage: node scripts/screenshot-pages.mjs [outputDir]
 *
 * Signs in once (in-memory OIDC); navigates via sidebar hash links — no full reload after auth.
 */
import { chromium } from '@playwright/test'
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
  await page.waitForTimeout(600)
}

async function capture(page, name) {
  await page.waitForTimeout(400)
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

await navTo(page, 'Listings')
await capture(page, 'listings')

await navTo(page, 'Hold queue')
await capture(page, 'hold-queue')

await navTo(page, 'Dead outbox')
await capture(page, 'outbox')

await browser.close()
console.log(`Wrote screenshots to ${outDir}`)
