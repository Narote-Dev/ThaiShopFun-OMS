import { expect, test, type Page } from '@playwright/test'

const jwtShape = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/

type Tokens = { access: string; id: string; refresh: string }

function watchTokens(page: Page): Tokens {
  const tokens: Tokens = { access: '', id: '', refresh: '' }
  page.on('response', (response) => {
    if (!response.url().includes('/tsf-idp/token') || response.status() !== 200) return
    void response
      .json()
      .then((body: { access_token?: string; id_token?: string; refresh_token?: string }) => {
        if (!body.access_token) return
        tokens.access = body.access_token
        tokens.id = body.id_token ?? ''
        tokens.refresh = body.refresh_token ?? ''
      })
      .catch(() => undefined)
  })
  return tokens
}

async function signIn(page: Page, shop: RegExp): Promise<Tokens> {
  const tokens = watchTokens(page)
  await page.goto('/')
  await page.getByRole('link', { name: shop }).click()
  return tokens
}

async function expectNoStoredTokens(page: Page, tokens: Tokens): Promise<void> {
  await expect.poll(() => tokens.access).not.toBe('')
  const entries = await page.evaluate(() => ({
    local: Object.entries(localStorage),
    session: Object.entries(sessionStorage),
  }))
  for (const [key, value] of [...entries.local, ...entries.session]) {
    expect(key.toLowerCase()).not.toContain('oidc.user')
    expect(value).not.toMatch(jwtShape)
    expect(value).not.toContain(tokens.access)
    if (tokens.refresh) expect(value).not.toContain(tokens.refresh)
    if (tokens.id) expect(value).not.toContain(tokens.id)
  }
  expect(page.url()).not.toContain('code=')
  expect(page.url()).not.toContain('state=')
}

test('active shop sees its name on the dashboard', async ({ page }) => {
  let authorization = ''
  page.on('request', (request) => {
    if (request.url().includes('/api/v1/me')) {
      authorization = request.headers().authorization ?? ''
    }
  })
  const tokens = await signIn(page, /^Active Shop/)
  await expect(page.getByRole('heading', { name: 'Active Shop' })).toBeVisible()
  await expect(page.getByText('OWNER', { exact: true })).toBeVisible()
  await expect.poll(() => authorization).toBe(`Bearer ${tokens.access}`)
  expect(tokens.access).not.toBe(tokens.id)
  await expectNoStoredTokens(page, tokens)
})

test('returns to the outbox page after sign-in', async ({ page }) => {
  await page.goto('/#/admin/outbox')
  await page.getByRole('link', { name: /^Active Shop/ }).click()
  await expect(page.getByRole('heading', { name: 'Dead outbox' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Active Shop' })).toHaveCount(0)
  expect(page.url()).toContain('#/admin/outbox')
  expect(page.url()).not.toContain('code=')
})

test('expired membership sees the paywall', async ({ page }) => {
  const tokens = await signIn(page, /^Expired Shop/)
  await expect(page.getByRole('heading', { name: 'Membership needed' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'TSF Seller Center' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Expired Shop' })).toHaveCount(0)
  await expectNoStoredTokens(page, tokens)
})

test('suspended membership sees the paywall', async ({ page }) => {
  await signIn(page, /^Suspended Shop/)
  await expect(page.getByRole('heading', { name: 'Membership needed' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Suspended Shop' })).toHaveCount(0)
})

test('grace membership shows the read-only banner', async ({ page }) => {
  await signIn(page, /^Grace Shop/)
  await expect(page.getByRole('heading', { name: 'Grace Shop' })).toBeVisible()
  await expect(page.getByRole('status')).toContainText(/read-only/i)
})

test('reload signs in again through authorize', async ({ page }) => {
  const tokens = await signIn(page, /^Active Shop/)
  await expect(page.getByRole('heading', { name: 'Active Shop' })).toBeVisible()
  const authorize = page.waitForRequest((request) => request.url().includes('/tsf-idp/authorize'))
  await page.reload()
  await authorize
  await page.getByRole('link', { name: /^Active Shop/ }).click()
  await expect(page.getByRole('heading', { name: 'Active Shop' })).toBeVisible()
  await expectNoStoredTokens(page, tokens)
})

test('log out returns to the signed-out screen', async ({ page }) => {
  await signIn(page, /^Active Shop/)
  await expect(page.getByRole('heading', { name: 'Active Shop' })).toBeVisible()
  await page.getByRole('button', { name: 'Log out' }).click()
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
})
