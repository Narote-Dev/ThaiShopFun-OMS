import { useEffect, useState } from 'react'
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
    <main className="wide">
      <p><a href="#/orders">← Orders</a></p>
      <h1>Hold queue</h1>
      {error ? <p role="alert">{error}</p> : null}
      <table aria-label="Hold groups">
        <thead>
          <tr>
            <th>Reason</th>
            <th>Detail</th>
            <th>Count</th>
            <th>Sample</th>
          </tr>
        </thead>
        <tbody>
          {groups.map((group) => (
            <tr key={`${group.hold_reason}-${group.hold_detail ?? ''}`}>
              <td>{group.hold_reason}</td>
              <td>
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
                      >
                        Map unmapped listings ({row.count})
                      </a>
                    </div>
                  ))
                ) : (
                  group.hold_detail ?? '—'
                )}
              </td>
              <td>{group.count}</td>
              <td>
                {group.samples[0] ? (
                  <a href={`#/orders/${group.samples[0].id}`}>{group.samples[0].external_order_id}</a>
                ) : (
                  '—'
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </main>
  )
}
