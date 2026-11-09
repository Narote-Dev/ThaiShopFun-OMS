import { apiRequest } from '../api/client'

export type ReconciliationIssue = {
  id: string
  rule: string
  status: 'OPEN' | 'ACK' | 'RESOLVED'
  order_id: string | null
  external_order_id: string | null
  details: Record<string, unknown>
  created_at: string
  updated_at: string
}

export const reconciliationApi = {
  list(status: string, rule: string): Promise<{ items: ReconciliationIssue[] }> {
    const params = new URLSearchParams()
    if (status) params.set('status', status)
    if (rule) params.set('rule', rule)
    const q = params.toString()
    return apiRequest(`/api/v1/reconciliation/issues${q ? `?${q}` : ''}`)
  },
  run(): Promise<{ run_id: string; issues_found: number; by_rule: Record<string, number> }> {
    return apiRequest('/api/v1/reconciliation/runs', { method: 'POST', body: '{}' })
  },
  patch(id: string, status: 'ACK' | 'RESOLVED'): Promise<{ id: string; status: string }> {
    return apiRequest(`/api/v1/reconciliation/issues/${id}`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    })
  },
}
