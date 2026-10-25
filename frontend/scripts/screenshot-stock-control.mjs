import { chromium, expect } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'

export async function captureStockControl(base, outDir) {
  await mkdir(outDir, { recursive: true })
  const browser = await chromium.launch(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH, args: ['--no-sandbox'] } : {})
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  const oms = process.env.OMS_BASE_URL ?? 'http://127.0.0.1:8080'
  const mode = async (value, reset = false) => {
    const response = await page.request.post(`${oms}/control/demo/stock-control-mode`, { data: { mode: value, reset_allowlist: reset } })
    if (!response.ok()) throw new Error(`Demo mode fixture: ${response.status()}`)
    return (await response.json()).channel_account_id
  }
  const capture = async (name, fullPage = true) => {
    await page.evaluate(async () => { window.scrollTo(0, 0); await document.fonts.ready; await new Promise(requestAnimationFrame) })
    await page.screenshot({ path: path.join(outDir, `${name}.png`), fullPage })
  }
  try {
    await page.goto(base)
    await page.getByRole('link', { name: /^Active Shop/ }).first().click()
    await expect(page.getByRole('heading', { name: 'Active Shop', exact: true })).toBeVisible()
    const seed = await page.request.post(`${oms}/control/demo/order-catalog`)
    if (!seed.ok()) throw new Error(`Demo catalog: ${seed.status()}`)
    const account = await mode('CONTROL', true)
    const url = `${base}/#/channel/listings?channel_account_id=${account}&mapped=all`
    await page.goto(url)
    await page.getByRole('checkbox', { name: 'เลือก L-demo-ready', exact: true }).waitFor()
    await expect(page.getByText('คุมเฉพาะที่เลือก', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'คุมสต็อกโดย OMS', exact: true }).first()).toHaveText('ไม่ได้คุม')
    await capture('listings-control')
    await page.getByRole('checkbox', { name: 'เลือก L-demo-ready', exact: true }).check()
    await page.getByRole('checkbox', { name: 'เลือก L-demo-cod', exact: true }).check()
    await page.getByRole('button', { name: 'เปิดคุมสต็อก', exact: true }).click()
    await expect(page.getByRole('dialog')).toContainText('2 รายการ')
    // The fixed overlay and sticky sidebar cover the viewport; fullPage includes offscreen content.
    await capture('listings-control-bulk-dialog', false)
    await page.getByRole('dialog').getByRole('button', { name: 'ยกเลิก', exact: true }).click()
    await mode('ACTIVE')
    await page.goto(`${base}/#/`)
    await expect(page.getByRole('heading', { name: 'Active Shop', exact: true })).toBeVisible()
    await page.goto(url)
    await expect(page.getByRole('checkbox')).toHaveCount(0)
    await expect(page.getByRole('table')).toContainText('L-demo-ready')
    await capture('listings-active-readonly')
    await mode('CONTROL')
    // A fresh browser context obtains the real STAFF session from the mock picker.
    const staff = await browser.newPage({ viewport: { width: 1440, height: 900 } })
    await staff.goto(base)
    await staff.getByRole('link', { name: /พนักงานร้านสาธิต/ }).click()
    await expect(staff.getByRole('heading', { name: 'Active Shop', exact: true })).toBeVisible()
    await staff.goto(url)
    await expect(staff.getByRole('table')).toContainText('L-demo-ready')
    await expect(staff.getByRole('checkbox')).toHaveCount(0)
    await staff.evaluate(async () => { window.scrollTo(0, 0); await document.fonts.ready; await new Promise(requestAnimationFrame) })
    await staff.screenshot({ path: path.join(outDir, 'listings-staff.png'), fullPage: true })
  } finally {
    await mode('ACTIVE').finally(() => browser.close())
  }
}
