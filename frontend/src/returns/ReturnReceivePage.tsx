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
import { Select } from '../ui/Select'
import { StatusBadge } from '../ui/StatusBadge'
import { labelTh } from '../ui/labels'
import { returnsApi, returnsMessage, type ReturnDetail } from './api'
import { useEffect, useMemo, useState } from 'react'

type PieceCondition = 'RESELLABLE' | 'DAMAGED' | ''

type LineDraft = { return_line_id: string; pieces: PieceCondition[] }

function emptyPieces(qty: number): PieceCondition[] {
  return Array.from({ length: qty }, () => '')
}

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
            pieces: emptyPieces(line.qty),
          })),
        )
        setError('')
      })
      .catch((err: unknown) => {
        if (active) setError(returnsMessage(err, labelTh('RETURN_LOAD_ERROR')))
      })
    return () => {
      active = false
    }
  }, [returnId])

  const pendingPieces = useMemo(
    () => drafts.reduce((sum, row) => sum + row.pieces.filter((piece) => piece !== '').length, 0),
    [drafts],
  )

  async function submitReceive() {
    if (!detail || !canReceive) return
    setBusy(true)
    setError('')
    try {
      const grouped = new Map<string, Map<PieceCondition, number>>()
      for (const row of drafts) {
        for (const piece of row.pieces) {
          if (piece !== 'RESELLABLE' && piece !== 'DAMAGED') continue
          const line = grouped.get(row.return_line_id) ?? new Map<PieceCondition, number>()
          line.set(piece, (line.get(piece) ?? 0) + 1)
          grouped.set(row.return_line_id, line)
        }
      }
      const lines = [...grouped.entries()].flatMap(([returnLineId, counts]) =>
        [...counts.entries()].map(([condition, qty]) => ({
          return_line_id: returnLineId,
          condition,
          qty,
        })),
      )
      if (lines.length === 0) {
        setError(labelTh('RETURN_RECEIVE_PICK_CONDITION'))
        return
      }
      await returnsApi.receive(returnId, lines)
      const refreshed = await returnsApi.detail(returnId)
      setDetail(refreshed)
      setDrafts(
        refreshed.lines.map((line) => ({
          return_line_id: line.return_line_id,
          pieces: emptyPieces(line.qty),
        })),
      )
    } catch (err: unknown) {
      setError(returnsMessage(err, labelTh('RETURN_RECEIVE_ERROR')))
    } finally {
      setBusy(false)
    }
  }

  if (!detail) {
    return (
      <PageContent>
        <PageHeader title={labelTh('RETURN_RECEIVE_TITLE')} />
        {error ? (
          <AlertBanner variant="danger" title={error} />
        ) : (
          <p className="text-[13px] text-stone-600">{labelTh('RETURN_LOADING')}</p>
        )}
      </PageContent>
    )
  }

  const received = detail.status === 'RECEIVED' || detail.status === 'CLOSED'

  return (
    <PageContent>
      <PageHeader
        title={labelTh('RETURN_RECEIVE_TITLE')}
        subtitle={`${labelTh('ORDER_NUMBER')} ${detail.external_order_id} · ${labelTh(detail.type, 'return_type')} · ${labelTh(detail.status, 'return_status')}`}
      />
      {error ? <AlertBanner variant="danger" className="mt-5" title={error} /> : null}
      <Card className="mt-4">
        <CardHeader title={labelTh('RETURN_LINES_TITLE')} />
        <CardBody>
          <DataTable aria-label={labelTh('RETURN_LINES_TITLE')}>
            <DataTableHead>
              <DataTableRow>
                <DataTableTh>{labelTh('PRODUCT')}</DataTableTh>
                <DataTableTh>{labelTh('QTY')}</DataTableTh>
                <DataTableTh>{labelTh('RETURN_CONDITION_COLUMN')}</DataTableTh>
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
                      <div className="space-y-1">
                        <StatusBadge kind="return_condition" value={line.condition} />
                        {line.restocked_qty > 0 ? (
                          <div className="text-[12px] text-stone-500">
                            {labelTh('RETURN_RESELLABLE_QTY')}: {line.restocked_qty}
                          </div>
                        ) : null}
                        {line.damaged_qty > 0 ? (
                          <div className="text-[12px] text-stone-500">
                            {labelTh('RETURN_DAMAGED_QTY')}: {line.damaged_qty}
                          </div>
                        ) : null}
                      </div>
                    ) : received || !detail.can_receive || !canReceive ? (
                      <span className="text-stone-500">—</span>
                    ) : (
                      <div className="space-y-2">
                        {Array.from({ length: line.qty }, (_, pieceIndex) => (
                          <Select
                            key={`${line.return_line_id}-${pieceIndex}`}
                            aria-label={`${line.name} ${labelTh('RETURN_PIECE')} ${pieceIndex + 1}`}
                            value={drafts[index]?.pieces[pieceIndex] ?? ''}
                            onChange={(event) => {
                              const value = event.target.value as PieceCondition
                              setDrafts((prev) =>
                                prev.map((row, rowIndex) =>
                                  rowIndex === index
                                    ? {
                                        ...row,
                                        pieces: row.pieces.map((piece, i) =>
                                          i === pieceIndex ? value : piece,
                                        ),
                                      }
                                    : row,
                                ),
                              )
                            }}
                          >
                            <option value="">{labelTh('RETURN_CONDITION_PLACEHOLDER')}</option>
                            <option value="RESELLABLE">{labelTh('RESELLABLE')}</option>
                            <option value="DAMAGED">{labelTh('DAMAGED')}</option>
                          </Select>
                        ))}
                      </div>
                    )}
                  </DataTableCell>
                </DataTableRow>
              ))}
            </tbody>
          </DataTable>
        </CardBody>
      </Card>
      {detail.can_receive && canReceive && !received ? (
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <Button type="button" disabled={busy} onClick={() => void submitReceive()}>
            {labelTh('RETURN_RECEIVE_CONFIRM')}
          </Button>
          <span className="text-[12px] text-stone-500">
            เลือกสภาพแล้ว {pendingPieces} ชิ้น
          </span>
        </div>
      ) : null}
      {!canReceive ? (
        <p className="mt-4 text-[13px] text-stone-600">{labelTh('RETURN_RECEIVE_OWNER_ONLY')}</p>
      ) : null}
    </PageContent>
  )
}
