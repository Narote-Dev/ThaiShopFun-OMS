import { catalogApi, type Sku } from './api'

export async function fetchAllSkus(): Promise<Sku[]> {
  const items: Sku[] = []
  let offset = 0
  for (;;) {
    const page = await catalogApi.listSkus('', 200, offset)
    items.push(...page.items)
    if (page.items.length === 0 || items.length >= page.total) break
    offset += page.items.length
  }
  return items
}
