import { useEffect, useState } from 'react'
import { reconciliationApi, type ReconciliationIssue } from './api'
import { AlertBanner } from '../ui/Alert'
import { StatusBadge } from '../ui/StatusBadge'
import { Button } from '../ui/Button'
import { DataTable, DataTableCell, DataTableHead, DataTableRow, DataTableTh, EmptyState } from '../ui/DataTable'
import { formatBangkokDateTime } from '../ui/format'
import { labelTh } from '../ui/labels'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'
import { Select } from '../ui/Select'

const RULES = [
  'SHIP_BY_LATE',
  'SHIPPED_UNPAID',
  'PAID_AFTER_CANCEL',
  'COD_COLLECTION_OVERDUE',
  'REFUND_MISSING_AFTER_RETURN',
  'PAYMENT_AMOUNT_MISMATCH',
  'STOCK_DRIFT',
  'INVARIANT_VIOLATION',
] as const

export default function ReconciliationPage({ canRun }: { canRun: boolean }) {
  const [status, setStatus] = useState('OPEN')
  const [rule, setRule] = useState('')
  const [issues, setIssues] = useState<ReconciliationIssue[]>([])
  const [loading, setLoading] = useState(true)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState(false)
  const [reload, setReload] = useState(0)

  useEffect(() => {
    let active = true
    reconciliationApi
      .list(status, rule)
      .then(({ items }) => {
        if (active) {
          setIssues(items)
          setError(false)
        }
      })
      .catch(() => {
        if (active) setError(true)
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [status, rule, reload])

  async function runNow() {
    setRunning(true)
    setError(false)
    try {
      await reconciliationApi.run()
      setLoading(true)
      setReload((n) => n + 1)
    } catch {
      setError(true)
    } finally {
      setRunning(false)
    }
  }

  async function setIssueStatus(id: string, next: 'ACK' | 'RESOLVED') {
    try {
      await reconciliationApi.patch(id, next)
      setReload((n) => n + 1)
    } catch {
      setError(true)
    }
  }

  return (
    <PageContent wide className="space-y-5">
      <PageHeader
        title={labelTh('RECONCILIATION')}
        subtitle={labelTh('RECONCILIATION_DESCRIPTION')}
        actions={
          canRun && (
            <Button disabled={running || loading} onClick={() => void runNow()}>
              {labelTh(running ? 'RECONCILIATION_RUNNING' : 'RECONCILIATION_RUN')}
            </Button>
          )
        }
      />
      <div className="flex flex-wrap gap-3">
        <Select aria-label={labelTh('RECONCILIATION_STATUS_FILTER')} value={status} onChange={(e) => {
          setLoading(true)
          setStatus(e.target.value)
        }}>
          <option value="">{labelTh('RECONCILIATION_STATUS_ALL')}</option>
          {['OPEN', 'ACK', 'RESOLVED'].map((value) => (
            <option key={value} value={value}>
              {labelTh(value)}
            </option>
          ))}
        </Select>
        <Select aria-label={labelTh('RECONCILIATION_RULE')} value={rule} onChange={(e) => {
          setLoading(true)
          setRule(e.target.value)
        }}>
          <option value="">{labelTh('RECONCILIATION_ALL_RULES')}</option>
          {RULES.map((value) => (
            <option key={value} value={value}>
              {labelTh(value)}
            </option>
          ))}
        </Select>
      </div>
      {error && (
        <AlertBanner
          variant="danger"
          title={labelTh('RECONCILIATION_ERROR')}
          actions={
            <Button
              variant="secondary"
              onClick={() => {
                setLoading(true)
                setReload((n) => n + 1)
              }}
            >
              {labelTh('RECONCILIATION_RETRY')}
            </Button>
          }
        />
      )}
      {loading ? (
        <p role="status">{labelTh('RECONCILIATION_LOADING')}</p>
      ) : issues.length === 0 ? (
        <EmptyState title={labelTh('RECONCILIATION_EMPTY')} />
      ) : (
        <DataTable aria-label={labelTh('RECONCILIATION')}>
          <DataTableHead>
            <DataTableRow>
              {[
                'RECONCILIATION_COL_STATUS',
                'RECONCILIATION_RULE',
                'ORDER_NUMBER',
                'RECONCILIATION_COL_CREATED',
                'RECONCILIATION_COL_UPDATED',
                'RECONCILIATION_COL_ACTIONS',
              ].map((key) => (
                  <DataTableTh key={key}>{labelTh(key)}</DataTableTh>
                ),
              )}
            </DataTableRow>
          </DataTableHead>
          <tbody>
            {issues.map((issue) => (
              <DataTableRow key={issue.id}>
                <DataTableCell>
                  <StatusBadge value={issue.status} kind="reconciliation_issue" />
                </DataTableCell>
                <DataTableCell>{labelTh(issue.rule)}</DataTableCell>
                <DataTableCell mono>
                  {issue.external_order_id ?? labelTh('RECONCILIATION_SHOP_LEVEL')}
                </DataTableCell>
                <DataTableCell>{formatBangkokDateTime(issue.created_at)}</DataTableCell>
                <DataTableCell>{formatBangkokDateTime(issue.updated_at)}</DataTableCell>
                <DataTableCell>
                  {canRun && issue.status === 'OPEN' && (
                    <Button size="sm" variant="secondary" onClick={() => void setIssueStatus(issue.id, 'ACK')}>
                      {labelTh('RECONCILIATION_ACK')}
                    </Button>
                  )}
                  {canRun && issue.status !== 'RESOLVED' && (
                    <Button size="sm" className="ms-2" onClick={() => void setIssueStatus(issue.id, 'RESOLVED')}>
                      {labelTh('RECONCILIATION_RESOLVE')}
                    </Button>
                  )}
                </DataTableCell>
              </DataTableRow>
            ))}
          </tbody>
        </DataTable>
      )}
    </PageContent>
  )
}
