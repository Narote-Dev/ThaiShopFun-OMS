import { ORDER_PAGE_SIZE, ordersApi, type OrderFilters, type OrderListItem } from './api'

export async function fetchAllOrders(
  filters: OrderFilters,
  pageSize = ORDER_PAGE_SIZE,
): Promise<{ items: OrderListItem[]; total: number }> {
  const items: OrderListItem[] = []
  let cursor: string | null = null
  let total = 0
  for (;;) {
    const page = await ordersApi.list(filters, pageSize, cursor)
    total = page.total
    items.push(...page.items)
    if (!page.next_cursor) break
    cursor = page.next_cursor
  }
  return { items, total }
}

export async function fetchOrderTotal(filters: OrderFilters): Promise<number> {
  const page = await ordersApi.list(filters, 1, null)
  return page.total
}
