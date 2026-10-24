import { useEffect, useState } from 'react'
import {
  DataTable,
  DataTableCell,
  DataTableHead,
  DataTableRow,
  DataTableTh,
} from '../ui/DataTable'
import { labelTh, holdDetailTh } from '../ui/labels'
import { HoldReasonBadge } from '../ui/HoldReasonBadge'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'
import { ordersApi, ordersMessage, type HoldGroup } from './api'

function fixActionLinks(group: HoldGroup) {
  if (group.hold_reason === 'SKU_NOT_MAPPED') {
    const rows =
      group.channel_account_counts?.length
        ? group.channel_account_counts
        : group.samples[0]?.channel_account_id
          ? [{ channel_account_id: group.samples[0].channel_account_id, count: group.count }]
          : []
    return rows.map((row) => (
      <div key={row.channel_account_id}>
        <a
          href={`#/channel/listings?channel_account_id=${encodeURIComponent(row.channel_account_id)}&mapped=false`}
          className="font-medium text-brand-700 hover:underline"
        >
          จับคู่รายการขายที่ยังไม่จับคู่ ({row.count})
        </a>
      </div>
    ))
  }
  if (group.hold_reason === 'OUT_OF_STOCK') {
    return (
      <a
        href="#/orders?hold_reason=OUT_OF_STOCK"
        className="font-medium text-brand-700 hover:underline"
      >
        ดูออเดอร์สต็อกไม่พอ ({group.count})
      </a>
    )
  }
  if (group.hold_reason === 'CHANNEL_CANCEL_PENDING') {
    return <a href="#/orders?hold_reason=CHANNEL_CANCEL_PENDING" className="font-medium text-brand-700 hover:underline">ดูออเดอร์รอยกเลิก ({group.count})</a>
  }
  if (group.hold_detail) {
    return (
      <a
        href={`#/orders?hold_reason=${encodeURIComponent(group.hold_reason)}&q=${encodeURIComponent(group.hold_detail)}`}
        className="font-medium text-brand-700 hover:underline"
      >
        ค้นหาออเดอร์ที่เกี่ยวข้อง
      </a>
    )
  }
  return (
    <a
      href={`#/orders?hold_reason=${encodeURIComponent(group.hold_reason)}`}
      className="font-medium text-brand-700 hover:underline"
    >
      ดูออเดอร์ในสถานะนี้
    </a>
  )
}

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
        if (active) setError(ordersMessage(err, 'โหลดคิวออเดอร์ค้างไม่สำเร็จ'))
      })
    return () => {
      active = false
    }
  }, [])

  return (
    <PageContent wide>
      <p className="text-[13px]">
        <a href="#/orders" className="font-medium text-brand-700 hover:underline">
          ← ออเดอร์
        </a>
      </p>
      <PageHeader
        className="mt-2"
        title={
          <>
            <span aria-hidden="true">คิวออเดอร์ค้าง</span>
            <span className="sr-only">คิวออเดอร์ค้าง</span>
          </>
        }
      />
      {error ? (
        <p role="alert" className="mt-4 text-[13px] text-red-700">
          {error}
        </p>
      ) : null}
      <div className="mt-4">
        <DataTable aria-label="กลุ่มออเดอร์ค้าง">
          <DataTableHead>
            <tr>
              <DataTableTh>เหตุผล</DataTableTh>
              <DataTableTh>รายละเอียด / การดำเนินการ</DataTableTh>
              <DataTableTh>จำนวน</DataTableTh>
              <DataTableTh>ตัวอย่าง</DataTableTh>
            </tr>
          </DataTableHead>
          <tbody>
            {groups.map((group) => (
              <DataTableRow key={`${group.hold_reason}-${group.hold_detail ?? ''}`}>
                <DataTableCell>
                  <HoldReasonBadge reason={group.hold_reason} detail={group.hold_detail} />
                  <span className="sr-only">{labelTh(group.hold_reason)}</span>
                </DataTableCell>
                <DataTableCell>
                  {fixActionLinks(group)}
                  {group.hold_detail && group.hold_reason !== 'SKU_NOT_MAPPED' ? (
                    <div className="mt-1 font-mono text-[11.5px] text-stone-500">{holdDetailTh(group.hold_detail)}</div>
                  ) : null}
                </DataTableCell>
                <DataTableCell>{group.count}</DataTableCell>
                <DataTableCell>
                  {group.samples[0] ? (
                    <a
                      href={`#/orders/${group.samples[0].id}`}
                      className="font-medium text-brand-700 hover:underline"
                    >
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
