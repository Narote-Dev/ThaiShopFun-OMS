import { useEffect, useState } from 'react'
import {
  DataTable,
  DataTableCell,
  DataTableHead,
  DataTableRow,
  DataTableTh,
} from '../ui/DataTable'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'
import { ordersApi, ordersMessage, type HoldGroup } from './api'

export default function HoldQueuePage() {
  const [groups, setGroups] = useState<HoldGroup[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    ordersApi
      .holds()
      .then((body) => {
        if (active) {
          setGroups(body.groups)
          setError('')
        }
      })
      .catch((err: unknown) => {
        if (active) setError(ordersMessage(err, 'Could not load hold queue'))
      })
    return () => {
      active = false
    }
  }, [])

  return (
    <PageContent wide>
      <p className="text-[13px]">
        <a href="#/orders" className="font-medium text-brand-700 hover:underline">
          ← Orders
        </a>
      </p>
      <PageHeader className="mt-2" title="Hold queue" />
      {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : null}
      <div className="mt-4">
        <DataTable aria-label="Hold groups">
          <DataTableHead>
            <tr>
              <DataTableTh>Reason</DataTableTh>
              <DataTableTh>Detail</DataTableTh>
              <DataTableTh>Count</DataTableTh>
              <DataTableTh>Sample</DataTableTh>
            </tr>
          </DataTableHead>
          <tbody>
            {groups.map((group) => (
              <DataTableRow key={`${group.hold_reason}-${group.hold_detail ?? ''}`}>
                <DataTableCell>{group.hold_reason}</DataTableCell>
                <DataTableCell>
                  {group.hold_reason === 'SKU_NOT_MAPPED' ? (
                    (group.channel_account_counts?.length
                      ? group.channel_account_counts
                      : group.samples[0]?.channel_account_id
                        ? [
                            {
                              channel_account_id: group.samples[0].channel_account_id,
                              count: group.count,
                            },
                          ]
                        : []
                    ).map((row) => (
                      <div key={row.channel_account_id}>
                        <a
                          href={`#/channel/listings?channel_account_id=${encodeURIComponent(
                            row.channel_account_id,
                          )}&mapped=false`}
                          className="font-medium text-brand-700 hover:underline"
                        >
                          Map unmapped listings ({row.count})
                        </a>
                      </div>
                    ))
                  ) : (
                    group.hold_detail ?? '—'
                  )}
                </DataTableCell>
                <DataTableCell>{group.count}</DataTableCell>
                <DataTableCell>
                  {group.samples[0] ? (
                    <a href={`#/orders/${group.samples[0].id}`} className="font-medium text-brand-700 hover:underline">
                      {group.samples[0].external_order_id}
                    </a>
                  ) : (
                    '—'
                  )}
                </DataTableCell>
              </DataTableRow>
            ))}
          </tbody>
        </DataTable>
      </div>
    </PageContent>
  )
}
