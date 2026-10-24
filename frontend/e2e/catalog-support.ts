import { expect, type Page } from '@playwright/test'

/** Wait for this search, so a previous page cannot satisfy a positive or empty assertion. */
export async function searchSkus(page: Page, query: string) {
  await page.getByLabel('ค้นหารหัสสินค้า').fill(query)
  const response = page.waitForResponse((reply) => {
    const url = new URL(reply.url())
    return reply.request().method() === 'GET' && url.pathname === '/api/v1/skus' && url.searchParams.get('q') === query
  })
  await page.getByRole('button', { name: 'ค้นหา' }).click()
  expect((await response).ok()).toBeTruthy()
  await expect(page.getByRole('button', { name: 'ค้นหา' })).toBeEnabled()
}
