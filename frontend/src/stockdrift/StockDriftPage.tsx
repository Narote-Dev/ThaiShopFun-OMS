import { useEffect, useState } from 'react'
import { driftApi, type DriftIssue } from './api'
import { AlertBanner } from '../ui/Alert'
import { Badge } from '../ui/Badge'
import { Button } from '../ui/Button'
import { DataTable, DataTableCell, DataTableHead, DataTableRow, DataTableTh, EmptyState } from '../ui/DataTable'
import { formatBangkokDateTime } from '../ui/format'
import { labelTh } from '../ui/labels'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'
import { Select } from '../ui/Select'

export default function StockDriftPage({ canRun }: { canRun: boolean }) {
  const [status, setStatus] = useState('OPEN')
  const [issues, setIssues] = useState<DriftIssue[]>([])
  const [loading, setLoading] = useState(true)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState(false)
  const [reload, setReload] = useState(0)
  useEffect(() => {
    let active = true
    driftApi.list(status).then(({ items }) => {
      if (active) { setIssues(items); setError(false) }
    }).catch(() => { if (active) setError(true) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [status, reload])
  async function run() {
    setRunning(true)
    setError(false)
    try { await driftApi.run(); setLoading(true); setReload((n) => n + 1) }
    catch { setError(true) }
    finally { setRunning(false) }
  }
  return <PageContent wide className="space-y-5">
    <PageHeader title={labelTh('STOCK_DRIFT')} subtitle={labelTh('DRIFT_DESCRIPTION')}
      actions={canRun && <Button disabled={running || loading} onClick={() => void run()}>
        {labelTh(running ? 'DRIFT_RUNNING' : 'DRIFT_RUN')}
      </Button>} />
    <Select aria-label={labelTh('DRIFT_STATUS')} value={status} onChange={(e) => {
      setLoading(true); setStatus(e.target.value)
    }}>
      <option value="">{labelTh('DRIFT_ALL')}</option>
      {['OPEN', 'ACK', 'RESOLVED'].map((value) => <option key={value} value={value}>{labelTh(value)}</option>)}
    </Select>
    {error && <AlertBanner variant="danger" title={labelTh('DRIFT_ERROR')}
      actions={<Button variant="secondary" onClick={() => { setLoading(true); setReload((n) => n + 1) }}>{labelTh('DRIFT_RETRY')}</Button>} />}
    {loading ? <p role="status">{labelTh('DRIFT_LOADING')}</p> : issues.length === 0 ?
      <EmptyState title={labelTh('DRIFT_EMPTY')} /> : <DataTable aria-label={labelTh('STOCK_DRIFT')}>
        <DataTableHead><DataTableRow>
          {['DRIFT_STATUS', 'DRIFT_LISTING', 'DRIFT_SKUS', 'DRIFT_EXPECTED', 'DRIFT_SEEN', 'DRIFT_CREATED', 'DRIFT_UPDATED', 'DRIFT_REPUSH'].map((key) => <DataTableTh key={key}>{labelTh(key)}</DataTableTh>)}
        </DataTableRow></DataTableHead>
        <tbody>{issues.flatMap((issue) => {
          const rows = issue.details.listings.length ? issue.details.listings : [null]
          return rows.map((listing) => <DataTableRow key={`${issue.id}-${listing?.listing_id ?? 'resolved'}`}>
            <DataTableCell><Badge variant={issue.status === 'RESOLVED' ? 'success' : issue.status === 'ACK' ? 'info' : 'warning'}>{labelTh(issue.status)}</Badge></DataTableCell>
            <DataTableCell mono>{listing?.external_listing_id ?? labelTh('DRIFT_CLEARED')}</DataTableCell>
            <DataTableCell mono>{listing?.sku_ids.join(', ') ?? '—'}</DataTableCell>
            <DataTableCell>{listing?.expected ?? '—'}</DataTableCell>
            <DataTableCell>{listing?.seen ?? '—'}</DataTableCell>
            <DataTableCell>{formatBangkokDateTime(issue.created_at)}</DataTableCell>
            <DataTableCell>{formatBangkokDateTime(issue.updated_at)}</DataTableCell>
            <DataTableCell>{formatBangkokDateTime(listing?.repush_enqueued_at)}</DataTableCell>
          </DataTableRow>)
        })}</tbody>
      </DataTable>}
  </PageContent>
}
