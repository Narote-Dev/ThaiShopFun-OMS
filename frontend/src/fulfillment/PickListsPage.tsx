import { Printer } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { Checkbox } from '../ui/Checkbox'
import { DataTable, DataTableCell, DataTableHead, DataTableRow, DataTableTh } from '../ui/DataTable'
import { AlertBanner } from '../ui/Alert'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'
import { labelTh, pickCreatedToast } from '../ui/labels'
import { formatBangkokDateTime } from '../ui/format'
import { useToast } from '../ui/useToast'
import { fulfillmentMessage, pickListsApi, type PickCandidate } from './api'

export default function PickListsPage() {
  const toast = useToast()
  const [items, setItems] = useState<PickCandidate[]>([])
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let active = true
    pickListsApi
      .candidates()
      .then((page) => {
        if (!active) return
        setItems(page.items)
        setError('')
      })
      .catch((err: unknown) => {
        if (active) setError(fulfillmentMessage(err, labelTh('PICK_LOAD_ERROR')))
      })
    return () => {
      active = false
    }
  }, [])

  const allSelected = items.length > 0 && items.every((row) => selected.has(row.id))

  async function createPickList() {
    if (selected.size === 0) return
    setBusy(true)
    setError('')
    try {
      const response = await pickListsApi.create([...selected])
      toast.push(pickCreatedToast(response.order_count), 'success')
      await pickListsApi.openPdf(response.id)
      setSelected(new Set())
      const page = await pickListsApi.candidates()
      setItems(page.items)
    } catch (err: unknown) {
      setError(fulfillmentMessage(err, labelTh('PICK_CREATE_ERROR')))
    } finally {
      setBusy(false)
    }
  }

  return (
    <PageContent wide>
      <PageHeader
        title={labelTh('PICK_LIST_TITLE')}
        subtitle={labelTh('PICK_LIST_SUBTITLE')}
        actions={
          <Button
            type="button"
            variant="primary"
            disabled={busy || selected.size === 0}
            onClick={() => void createPickList()}
          >
            <Printer className="h-4 w-4" />
            {labelTh('PICK_CREATE_BUTTON')}
          </Button>
        }
      />

      {error ? (
        <AlertBanner variant="danger" className="mt-5" title={error} />
      ) : (
        <AlertBanner variant="info" className="mt-5" title={labelTh('PICK_LIST_HINT')} />
      )}

      <Card className="mt-5 overflow-hidden">
        <DataTable aria-label={labelTh('PICK_LIST_TITLE')} className="rounded-none border-0 shadow-none">
          <DataTableHead>
            <tr>
              <DataTableTh className="w-10">
                <Checkbox
                  aria-label={labelTh('PICK_SELECT_ALL')}
                  checked={allSelected}
                  onCheckedChange={(checked) => {
                    if (!checked) setSelected(new Set())
                    else setSelected(new Set(items.map((row) => row.id)))
                  }}
                />
              </DataTableTh>
              <DataTableTh>{labelTh('ORDER_NUMBER')}</DataTableTh>
              <DataTableTh>{labelTh('ORDERED_AT')}</DataTableTh>
              <DataTableTh>{labelTh('SHIP_BY')}</DataTableTh>
              <DataTableTh>{labelTh('LINE_COUNT')}</DataTableTh>
            </tr>
          </DataTableHead>
          <tbody>
            {items.map((row) => (
              <DataTableRow key={row.id}>
                <DataTableCell>
                  <Checkbox
                    aria-label={`${labelTh('PICK_SELECT_ORDER')} ${row.external_order_id}`}
                    checked={selected.has(row.id)}
                    onCheckedChange={(checked) => {
                      const next = new Set(selected)
                      if (checked) next.add(row.id)
                      else next.delete(row.id)
                      setSelected(next)
                    }}
                  />
                </DataTableCell>
                <DataTableCell>
                  <a
                    href={`#/fulfillment/pack/${row.id}`}
                    className="font-mono text-[12.5px] font-medium text-brand-700 hover:underline"
                  >
                    {row.external_order_id}
                  </a>
                </DataTableCell>
                <DataTableCell>{formatBangkokDateTime(row.ordered_at)}</DataTableCell>
                <DataTableCell>
                  {row.ship_by ? formatBangkokDateTime(row.ship_by) : '—'}
                </DataTableCell>
                <DataTableCell>{row.line_count}</DataTableCell>
              </DataTableRow>
            ))}
          </tbody>
        </DataTable>
        {items.length === 0 ? (
          <p className="p-6 text-[13px] text-stone-600">{labelTh('PICK_EMPTY')}</p>
        ) : null}
      </Card>
    </PageContent>
  )
}
