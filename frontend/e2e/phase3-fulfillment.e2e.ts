import { expect, test } from '@playwright/test'
import {
  OMS,
  assertInvariants,
  createPrepaidOrder,
  orderDetail,
  ownerToken,
  seedDemoCatalog,
  sendOrderCancelled,
  sendRefundSucceeded,
  sendReturnDecided,
  sendReturnRequested,
  sendShipmentDelivered,
  waitForOrder,
} from './phase3-support'

async function loginActiveShop(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.getByRole('link', { name: /^Active Shop/ }).click()
  await expect(page.getByRole('heading', { name: 'Active Shop', exact: true })).toBeVisible()
}

test('phase 3 cancel at PACKED releases reservation and clears pack progress', async ({ page, request }) => {
  test.setTimeout(180_000)
  const token = await ownerToken(request)
  await seedDemoCatalog(request)
  const externalOrderId = `E2E-PACK-CXL-${Date.now()}`
  await createPrepaidOrder(request, externalOrderId, [
    { line_id: 'L1', listing_sku_id: 'L-demo-ready', qty: 1, unit_price: 295 },
  ])
  const { id: orderId } = await waitForOrder(request, token, externalOrderId, 'READY_TO_PICK')

  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
  let pickIssued = false
  let scanDone = false
  await expect
    .poll(
      async () => {
        const detail = await orderDetail(request, token, orderId)
        if (detail.fulfillment_status === 'PACKED') return 'packed'
        if (!pickIssued && detail.fulfillment_status === 'READY_TO_PICK') {
          const pick = await request.post(`${OMS}/api/v1/pick-lists`, {
            headers,
            data: { order_ids: [orderId] },
          })
          if (pick.ok()) pickIssued = true
          return ''
        }
        if ((detail.fulfillment_status === 'PICKING' || pickIssued) && !scanDone) {
          const scan = await request.post(`${OMS}/api/v1/pack/orders/${orderId}/scans`, {
            headers,
            data: { scan: 'DEMO-SKU-READY' },
          })
          if (!scan.ok()) return ''
          scanDone = true
          const packed = await request.post(`${OMS}/api/v1/pack/orders/${orderId}/complete`, {
            headers,
            data: {},
          })
          return packed.ok() ? 'packed' : ''
        }
        return ''
      },
      { timeout: 120_000, intervals: [2000] },
    )
    .toBe('packed')

  await loginActiveShop(page)
  await page.goto(`/#/orders/${orderId}`)
  await expect(page.locator('dt:has-text("การจัดส่ง") + dd')).toContainText('แพ็กแล้ว', {
    timeout: 30_000,
  })

  await sendOrderCancelled(request, externalOrderId)
  await expect
    .poll(
      async () => {
        const detail = await orderDetail(request, token, orderId)
        return `${detail.order_status}|${detail.fulfillment_status}`
      },
      { timeout: 90_000, intervals: [2000] },
    )
    .toBe('CANCELLED|UNFULFILLED')

  await expect
    .poll(
      async () => {
        const after = await orderDetail(request, token, orderId)
        const reservations = (after.reservations as Array<{ status: string }>) ?? []
        return reservations.every((row) => row.status !== 'ACTIVE') ? 'released' : ''
      },
      { timeout: 90_000, intervals: [2000] },
    )
    .toBe('released')
  await assertInvariants(request)
})

test('phase 3 full flow: reserve through partial return and refund snapshot', async ({
  page,
  request,
}) => {
  test.setTimeout(240_000)
  const token = await ownerToken(request)
  await seedDemoCatalog(request)
  const externalOrderId = `E2E-FLOW-${Date.now()}`
  const returnId = `e2e-ret-${Date.now()}`
  const refundId = `e2e-rf-${Date.now()}`

  await createPrepaidOrder(request, externalOrderId, [
    { line_id: 'L1', listing_sku_id: 'L-demo-ready', qty: 1, unit_price: 295 },
    { line_id: 'L2', listing_sku_id: 'L-demo-cod', qty: 1, unit_price: 285 },
  ])
  const { id: orderId } = await waitForOrder(request, token, externalOrderId, 'READY_TO_PICK')

  await loginActiveShop(page)
  await page.getByRole('navigation', { name: 'เมนูหลัก', exact: true }).getByRole('link', {
    name: 'ใบหยิบสินค้า',
    exact: true,
  }).click()
  await expect(page.getByRole('heading', { name: 'ใบหยิบสินค้า', exact: true })).toBeVisible()
  await expect
    .poll(
      async () =>
        await page
          .getByRole('checkbox', { name: `เลือกออเดอร์ ${externalOrderId}`, exact: true })
          .count(),
      { timeout: 90_000, intervals: [2000] },
    )
    .toBeGreaterThan(0)
  await page.getByRole('checkbox', { name: `เลือกออเดอร์ ${externalOrderId}`, exact: true }).check()
  await page.getByRole('button', { name: 'สร้างใบหยิบและพิมพ์', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('สร้างใบหยิบ', { timeout: 30_000 })

  await page.goto(`/#/fulfillment/pack/${orderId}`)
  await expect(page.getByRole('heading', { name: 'แพ็กสินค้า', exact: true })).toBeVisible({
    timeout: 60_000,
  })
  const scan = page.getByLabel('สแกนบาร์โค้ดหรือรหัสสินค้า', { exact: true })
  await scan.fill('DEMO-SKU-READY')
  await scan.press('Enter')
  await scan.fill('DEMO-SKU-COD')
  await scan.press('Enter')
  await page.getByRole('button', { name: 'ยืนยันแพ็กเสร็จ', exact: true }).click()
  await expect(page.getByText('แพ็กเสร็จแล้ว', { exact: true })).toBeVisible({ timeout: 30_000 })

  await page.getByRole('navigation', { name: 'เมนูหลัก', exact: true }).getByRole('link', {
    name: 'ยืนยันจัดส่ง',
    exact: true,
  }).click()
  await expect
    .poll(
      async () => await page.getByRole('row').filter({ hasText: externalOrderId }).count(),
      { timeout: 90_000, intervals: [2000] },
    )
    .toBeGreaterThan(0)
  const row = page.getByRole('row').filter({ hasText: externalOrderId })
  await row.getByRole('checkbox').check()
  await page.getByRole('button', { name: 'พิมพ์ใบปะหน้า (A6)', exact: true }).click()
  await expect
    .poll(
      async () => {
        const detail = await orderDetail(request, token, orderId)
        const shipment = (detail.shipments as Array<{ status?: string }> | undefined)?.[0]
        return shipment?.status === 'LABEL_READY' ? 1 : 0
      },
      { timeout: 120_000, intervals: [2000] },
    )
    .toBe(1)
  await row.getByRole('button', { name: 'ยืนยันส่ง', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'ยืนยัน', exact: true }).click()
  await expect(page.getByText('บันทึกการจัดส่งแล้ว', { exact: true })).toBeVisible({ timeout: 30_000 })

  await waitForOrder(request, token, externalOrderId, 'SHIPPED')
  const shipped = await orderDetail(request, token, orderId)
  const tracking =
    ((shipped.shipments as Array<{ tracking_no?: string }>) ?? [])[0]?.tracking_no ?? 'E2E-TRACK'
  await sendShipmentDelivered(request, externalOrderId, tracking)
  await waitForOrder(request, token, externalOrderId, 'DELIVERED')

  await sendReturnRequested(request, externalOrderId, returnId, 'L1', 1)
  await sendReturnDecided(request, externalOrderId, returnId)
  await expect
    .poll(
      async () => {
        const detail = await orderDetail(request, token, orderId)
        const returns = detail.returns as Array<{ id: string; receive_url?: string }> | undefined
        return returns?.find((row) => row.receive_url)?.receive_url ?? ''
      },
      { timeout: 90_000, intervals: [2000] },
    )
    .toMatch(/#\/returns\//)

  const detailBeforeReturn = await orderDetail(request, token, orderId)
  const receiveUrl = (detailBeforeReturn.returns as Array<{ receive_url: string }>)[0].receive_url
  await page.goto(receiveUrl)
  await expect(page.getByRole('heading', { name: 'รับของคืน', exact: true })).toBeVisible()
  await page.getByLabel(/E2E item ชิ้นที่ 1/).selectOption('RESELLABLE')
  await page.getByRole('button', { name: 'ยืนยันรับคืน', exact: true }).click()
  await expect
    .poll(
      async () => {
        const detail = await orderDetail(request, token, orderId)
        return (detail.returns as Array<{ status: string }>)[0]?.status ?? ''
      },
      { timeout: 60_000, intervals: [2000] },
    )
    .toMatch(/RECEIVED|CLOSED/)

  await sendRefundSucceeded(request, externalOrderId, refundId, returnId, 295)
  await page.goto(`/#/orders/${orderId}`)
  await expect(page.getByRole('heading', { name: `ออเดอร์ ${externalOrderId}`, exact: true })).toBeVisible()
  await expect
    .poll(
      async () => await page.getByText('คืนเงินบางส่วน', { exact: true }).count(),
      { timeout: 90_000, intervals: [2000] },
    )
    .toBeGreaterThan(0)
  await expect(page.getByText('฿295.00')).toBeVisible()
  await assertInvariants(request)
})
