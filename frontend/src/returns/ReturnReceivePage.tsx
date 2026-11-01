import type { Me } from '../auth/AuthContext'
import { AlertBanner } from '../ui/Alert'
import { Button } from '../ui/Button'
import { Card, CardBody, CardHeader } from '../ui/Card'
import {
  DataTable,
  DataTableCell,
  DataTableHead,
  DataTableRow,
  DataTableTh,
} from '../ui/DataTable'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'
import { StatusBadge } from '../ui/StatusBadge'
import { labelTh } from '../ui/labels'
import { returnsApi, returnsMessage, type ReturnDetail } from './api'
import { useEffect, useState } from 'react'

type LineDraft = { return_line_id: string; condition: 'RESELLABLE' | 'DAMAGED' | '' }

export default function ReturnReceivePage({ returnId, me }: { returnId: string; me: Me }) {
  const canReceive = me.role === 'OWNER' || me.role === 'ADMIN'
  const [detail, setDetail] = useState<ReturnDetail | null>(null)
  const [drafts, setDrafts] = useState<LineDraft[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let active = true
    returnsApi
      .detail(returnId)
      .then((loaded) => {
        if (!active) return
        setDetail(loaded)
        setDrafts(
          loaded.lines.map((line) => ({
            return_line_id: line.return_line_id,
            condition: (line.condition as LineDraft['condition']) || '',
          })),
        )
        setError('')
      })
      .catch((err: unknown) => {
        if (active) setError(returnsMessage(err, 'โหลดคำขอคืนไม่สำเร็จ'))
      })
    return () => {
      active = false
    }
  }, [returnId])

  async function submitReceive() {
    if (!detail || !canReceive) return
    setBusy(true)
    setError('')
    try {
      const lines = drafts
        .filter((row) => row.condition === 'RESELLABLE' || row.condition === 'DAMAGED')
        .map((row) => ({ return_line_id: row.return_line_id, condition: row.condition }))
      if (lines.length === 0) {
        setError('เลือกสภาพสินค้าอย่างน้อยหนึ่งบรรทัด')
        return
      }
      await returnsApi.receive(returnId, lines)
      const refreshed = await returnsApi.detail(returnId)
      setDetail(refreshed)
      setDrafts(
        refreshed.lines.map((line) => ({
          return_line_id: line.return_line_id,
          condition: (line.condition as LineDraft['condition']) || '',
        })),
      )
    } catch (err: unknown) {
      setError(returnsMessage(err, 'บันทึกรับของคืนไม่สำเร็จ'))
    } finally {
      setBusy(false)
    }
  }

  if (!detail) {
    return (
      <PageContent>
        <PageHeader title="รับของคืน" />
        {error ? (
          <AlertBanner variant="danger" title={error} />
        ) : (
          <p className="text-[13px] text-stone-600">กำลังโหลด…</p>
        )}
      </PageContent>
    )
  }

  const received = detail.status === 'RECEIVED' || detail.status === 'CLOSED'

  return (
    <PageContent>
      <PageHeader
        title="รับของคืน"
        subtitle={`ออเดอร์ ${detail.external_order_id} · ${labelTh(detail.type, 'return_type')} · ${labelTh(detail.status, 'return_status')}`}
      />
      {error ? <AlertBanner variant="danger" className="mt-5" title={error} /> : null}
      <Card className="mt-4">
        <CardHeader title="รายการคืน" />
        <CardBody>
          <DataTable aria-label="รายการคืนสินค้า">
            <DataTableHead>
              <DataTableRow>
                <DataTableTh>สินค้า</DataTableTh>
                <DataTableTh>จำนวน</DataTableTh>
                <DataTableTh>สภาพเมื่อรับ</DataTableTh>
              </DataTableRow>
            </DataTableHead>
            <tbody>
              {detail.lines.map((line, index) => (
                <DataTableRow key={line.return_line_id}>
                  <DataTableCell>
                    <div className="font-medium text-stone-900">{line.name}</div>
                    <div className="text-[12px] text-stone-500">{line.external_line_id}</div>
                  </DataTableCell>
                  <DataTableCell>{line.qty}</DataTableCell>
                  <DataTableCell>
                    {line.condition ? (
                      <StatusBadge kind="return_condition" value={line.condition} />
                    ) : received || !detail.can_receive || !canReceive ? (
                      <span className="text-stone-500">—</span>
                    ) : (
                      <select
                        className="rounded border border-stone-300 px-2 py-1 text-[13px]"
                        value={drafts[index]?.condition ?? ''}
                        onChange={(event) => {
                          const value = event.target.value as LineDraft['condition']
                          setDrafts((prev) =>
                            prev.map((row, i) =>
                              i === index ? { ...row, condition: value } : row,
                            ),
                          )
                        }}
                      >
                        <option value="">เลือกสภาพ</option>
                        <option value="RESELLABLE">{labelTh('RESELLABLE')}</option>
                        <option value="DAMAGED">{labelTh('DAMAGED')}</option>
                      </select>
                    )}
                  </DataTableCell>
                </DataTableRow>
              ))}
            </tbody>
          </DataTable>
        </CardBody>
      </Card>
      {detail.can_receive && canReceive && !received ? (
        <div className="mt-4">
          <Button type="button" disabled={busy} onClick={() => void submitReceive()}>
            ยืนยันรับของคืน
          </Button>
        </div>
      ) : null}
      {!canReceive ? (
        <p className="mt-4 text-[13px] text-stone-600">เฉพาะผู้ดูแลร้านจึงจะบันทึกรับของคืนได้</p>
      ) : null}
    </PageContent>
  )
}
