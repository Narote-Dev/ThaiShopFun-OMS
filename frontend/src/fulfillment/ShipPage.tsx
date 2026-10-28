import { useEffect, useState } from 'react'
import { AlertBanner } from '../ui/Alert'
import { Button } from '../ui/Button'
import { Checkbox } from '../ui/Checkbox'
import { ConfirmationDialog } from '../ui/ConfirmationDialog'
import { Card } from '../ui/Card'
import { DataTable, DataTableCell, DataTableHead, DataTableRow, DataTableTh } from '../ui/DataTable'
import { Input } from '../ui/Input'
import { Label } from '../ui/Label'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'
import { labelTh, shipManualSavedToast } from '../ui/labels'
import { formatBangkokDateTime } from '../ui/format'
import { useToast } from '../ui/useToast'
import { fulfillmentMessage, shipApi, type ShipCandidate } from './api'

export default function ShipPage() {
  const toast = useToast()
  const [rows, setRows] = useState<ShipCandidate[]>([])
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState('')
  const [manualOrderId, setManualOrderId] = useState<string | null>(null)
  const [carrier, setCarrier] = useState('')
  const [tracking, setTracking] = useState('')
  const [busy, setBusy] = useState(false)
  const [confirmOrderId, setConfirmOrderId] = useState<string | null>(null)

  async function refreshCandidates() {
    const data = await shipApi.candidates()
    setRows(data.items)
    setSelected(new Set())
    setError('')
  }

  useEffect(() => {
    let active = true
    shipApi
      .candidates()
      .then((data) => {
        if (!active) return
        setRows(data.items)
        setSelected(new Set())
        setError('')
      })
      .catch((err: unknown) => {
        if (active) setError(fulfillmentMessage(err, labelTh('SHIP_LOAD_ERROR')))
      })
      .finally(() => {
        if (active) setLoaded(true)
      })
    return () => {
      active = false
    }
  }, [])

  const toggle = (id: string) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const toggleAll = () => {
    if (selected.size === rows.length) setSelected(new Set())
    else setSelected(new Set(rows.map((r) => r.id)))
  }

  const printLabels = async () => {
    if (selected.size === 0) return
    setBusy(true)
    setError('')
    try {
      await shipApi.openLabelBatch([...selected])
      toast.push(labelTh('SHIP_LABELS_OPENED'), 'success')
    } catch (err: unknown) {
      setError(fulfillmentMessage(err, labelTh('SHIP_LABELS_ERROR')))
    } finally {
      setBusy(false)
    }
  }

  const runConfirmShip = async () => {
    if (!confirmOrderId) return
    setBusy(true)
    setError('')
    try {
      await shipApi.confirmShip(confirmOrderId)
      toast.push(labelTh('SHIP_CONFIRMED'), 'success')
      setConfirmOrderId(null)
      await refreshCandidates()
    } catch (err: unknown) {
      setError(fulfillmentMessage(err, labelTh('SHIP_CONFIRM_ERROR')))
    } finally {
      setBusy(false)
    }
  }

  const saveManual = async () => {
    if (!manualOrderId) return
    setBusy(true)
    setError('')
    try {
      await shipApi.manualTracking(manualOrderId, {
        carrier: carrier.trim(),
        tracking_no: tracking.trim(),
      })
      toast.push(shipManualSavedToast(), 'success')
      setManualOrderId(null)
      setCarrier('')
      setTracking('')
      await refreshCandidates()
    } catch (err: unknown) {
      setError(fulfillmentMessage(err, labelTh('SHIP_MANUAL_ERROR')))
    } finally {
      setBusy(false)
    }
  }

  return (
    <PageContent wide>
      <PageHeader
        title={labelTh('SHIP_TITLE')}
        subtitle={labelTh('SHIP_SUBTITLE')}
        actions={
          <Button type="button" disabled={selected.size === 0 || busy} onClick={() => void printLabels()}>
            {labelTh('SHIP_PRINT_LABELS')}
          </Button>
        }
      />
      {error ? <AlertBanner variant="danger" className="mt-5" title={error} /> : null}
      {manualOrderId ? (
        <div className="mt-5 rounded-lg border border-stone-200 bg-white p-5 shadow-sm">
          <h2 className="text-[15px] font-semibold text-stone-900">{labelTh('SHIP_MANUAL_TITLE')}</h2>
          <p className="mt-1 text-[13px] text-stone-600">{labelTh('SHIP_MANUAL_HINT')}</p>
          <div className="mt-4 grid gap-4 sm:grid-cols-2">
            <div>
              <Label htmlFor="ship-carrier">{labelTh('SHIP_CARRIER')}</Label>
              <Input id="ship-carrier" value={carrier} onChange={(e) => setCarrier(e.target.value)} />
            </div>
            <div>
              <Label htmlFor="ship-tracking">{labelTh('SHIP_TRACKING')}</Label>
              <Input id="ship-tracking" value={tracking} onChange={(e) => setTracking(e.target.value)} />
            </div>
          </div>
          <div className="mt-4 flex gap-2">
            <Button type="button" disabled={busy} onClick={() => void saveManual()}>
              {labelTh('SHIP_MANUAL_SAVE')}
            </Button>
            <Button type="button" variant="secondary" onClick={() => setManualOrderId(null)}>
              {labelTh('SHIP_MANUAL_CANCEL')}
            </Button>
          </div>
        </div>
      ) : null}
      <Card className="mt-5 overflow-hidden">
        <DataTable aria-label={labelTh('SHIP_TITLE')} className="rounded-none border-0 shadow-none">
          <DataTableHead>
            <DataTableRow>
              <DataTableTh className="w-10">
                <Checkbox
                  checked={rows.length > 0 && selected.size === rows.length}
                  aria-label={labelTh('SHIP_SELECT_ALL')}
                  onCheckedChange={toggleAll}
                />
              </DataTableTh>
              <DataTableTh>{labelTh('ORDER_NUMBER')}</DataTableTh>
              <DataTableTh>{labelTh('SHIP_BY')}</DataTableTh>
              <DataTableTh>{labelTh('SHIP_TRACKING')}</DataTableTh>
              <DataTableTh>{labelTh('SHIP_CARRIER')}</DataTableTh>
              <DataTableTh>{labelTh('SHIP_ACTIONS')}</DataTableTh>
            </DataTableRow>
          </DataTableHead>
          <tbody>
            {rows.map((row) => (
              <DataTableRow key={row.id}>
                <DataTableCell>
                  <Checkbox
                    checked={selected.has(row.id)}
                    aria-label={`${labelTh('SHIP_SELECT_ORDER')} ${row.external_order_id}`}
                    onCheckedChange={() => toggle(row.id)}
                  />
                </DataTableCell>
                <DataTableCell className="font-medium">{row.external_order_id}</DataTableCell>
                <DataTableCell>{row.ship_by ? formatBangkokDateTime(row.ship_by) : '—'}</DataTableCell>
                <DataTableCell>{row.tracking_no ?? '—'}</DataTableCell>
                <DataTableCell>{row.carrier ?? '—'}</DataTableCell>
                <DataTableCell>
                  <div className="flex flex-wrap gap-2">
                    <Button type="button" size="sm" disabled={busy} onClick={() => setConfirmOrderId(row.id)}>
                      {labelTh('SHIP_CONFIRM')}
                    </Button>
                    <Button
                      type="button"
                      size="sm"
                      variant="secondary"
                      disabled={busy}
                      onClick={() => setManualOrderId(row.id)}
                    >
                      {labelTh('SHIP_MANUAL_BUTTON')}
                    </Button>
                  </div>
                </DataTableCell>
              </DataTableRow>
            ))}
          </tbody>
        </DataTable>
        {!loaded ? (
          <p className="p-6 text-[13px] text-stone-600">{labelTh('SHIP_LOADING')}</p>
        ) : rows.length === 0 ? (
          <p className="p-6 text-[13px] text-stone-600">{labelTh('SHIP_EMPTY')}</p>
        ) : null}
      </Card>
      <ConfirmationDialog
        open={confirmOrderId !== null}
        onOpenChange={(open) => {
          if (!open && !busy) setConfirmOrderId(null)
        }}
        title={labelTh('SHIP_CONFIRM_DIALOG_TITLE')}
        description={labelTh('SHIP_CONFIRM_DIALOG_DESC')}
        danger
        onConfirm={() => void runConfirmShip()}
      />
    </PageContent>
  )
}
