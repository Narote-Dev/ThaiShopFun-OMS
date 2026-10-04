/**
 * Playwright screenshot helper for PR / docs (run with stack up: npm run dev + backend).
 * Usage: node scripts/screenshot-pages.mjs [outputDir]
 */
import { chromium } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'

const base = process.env.PLAYWRIGHT_BASE_URL ?? 'http://127.0.0.1:5173'
const outDir = path.resolve(process.argv[2] ?? 'docs/ui/screenshots')

const routes = [
  { name: 'login', path: '/', signedIn: false },
  { name: 'dashboard', path: '/#/', signedIn: true },
  { name: 'orders', path: '/#/orders', signedIn: true },
  { name: 'order-detail-demo', path: '/#/orders', signedIn: true, note: 'capture after seed' },
  { name: 'skus', path: '/#/catalog/skus', signedIn: true },
  { name: 'products', path: '/#/catalog/products', signedIn: true },
  { name: 'import', path: '/#/catalog/import', signedIn: true },
  { name: 'warehouses', path: '/#/warehouses', signedIn: true },
  { name: 'stock-documents', path: '/#/stock/documents', signedIn: true },
  { name: 'listings', path: '/#/channel/listings', signedIn: true },
  { name: 'hold-queue', path: '/#/orders/holds', signedIn: true },
  { name: 'outbox', path: '/#/admin/outbox', signedIn: true },
]

async function signIn(page) {
  await page.goto(base)
  const shop = page.getByRole('link', { name: /^Active Shop/ })
  if (await shop.count()) {
    await shop.click()
    await page.getByRole('heading', { name: 'Active Shop' }).waitFor({ timeout: 60_000 })
  }
}

await mkdir(outDir, { recursive: true })
const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })

for (const route of routes) {
  if (route.signedIn) await signIn(page)
  await page.goto(`${base}${route.path}`)
  await page.waitForTimeout(800)
  await page.screenshot({ path: path.join(outDir, `${route.name}.png`), fullPage: true })
}

await browser.close()
console.log(`Wrote screenshots to ${outDir}`)
