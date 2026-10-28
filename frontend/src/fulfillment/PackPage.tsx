import { Barcode, CheckCircle2 } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { AlertBanner } from '../ui/Alert'
import { Input } from '../ui/Input'
import { Label } from '../ui/Label'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'
import { StatusBadge } from '../ui/StatusBadge'
import { labelTh, packScanProgressToast } from '../ui/labels'
import { useToast } from '../ui/useToast'
import { fulfillmentMessage, packApi, type PackSession } from './api'

type Props = { orderId: string }

export default function PackPage({ orderId }: Props) {
  const toast = useToast()
  const inputRef = useRef<HTMLInputElement>(null)
  const [session, setSession] = useState<PackSession | null>(null)
  const [scan, setScan] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let active = true
    packApi
      .session(orderId)
      .then((next) => {
        if (!active) return
        setSession(next)
        setError('')
      })
      .catch((err: unknown) => {
        if (active) setError(fulfillmentMessage(err, labelTh('PACK_LOAD_ERROR')))
      })
    return () => {
      active = false
    }
  }, [orderId])

  async function submitScan(event: FormEvent) {
    event.preventDefault()
    if (!scan.trim()) return
    setBusy(true)
    setError('')
    try {
      const result = await packApi.scan(orderId, scan.trim())
      setScan('')
      const refreshed = await packApi.session(orderId)
      setSession(refreshed)
      toast.push(packScanProgressToast(result.sku_code, result.packed_qty, result.required_qty))
      if (result.complete) {
        toast.push(labelTh('PACK_SCAN_COMPLETE'), 'success')
      }
      inputRef.current?.focus()
    } catch (err: unknown) {
      setError(fulfillmentMessage(err, labelTh('PACK_SCAN_ERROR')))
    } finally {
      setBusy(false)
    }
  }

  async function completePack() {
    setBusy(true)
    setError('')
    try {
      const result = await packApi.complete(orderId)
      toast.push(labelTh('PACK_DONE'), 'success')
      const refreshed = await packApi.session(orderId)
      setSession({ ...refreshed, fulfillment_status: result.fulfillment_status })
    } catch (err: unknown) {
      setError(fulfillmentMessage(err, labelTh('PACK_COMPLETE_ERROR')))
    } finally {
      setBusy(false)
    }
  }

  if (!session && !error) {
    return (
      <PageContent>
        <p className="text-[13px] text-stone-600">{labelTh('PACK_LOADING')}</p>
      </PageContent>
    )
  }

  return (
    <PageContent wide>
      <PageHeader
        title={labelTh('PACK_TITLE')}
        subtitle={session ? session.external_order_id : orderId}
        actions={
          session?.fulfillment_status === 'PICKING' ? (
            <Button
              type="button"
              variant="primary"
              disabled={busy || !session.complete}
              onClick={() => void completePack()}
            >
              <CheckCircle2 className="h-4 w-4" />
              {labelTh('PACK_CONFIRM_BUTTON')}
            </Button>
          ) : null
        }
      />

      {error ? <AlertBanner variant="danger" className="mt-5" title={error} /> : null}

      {session ? (
        <>
          <div className="mt-5 grid gap-4 lg:grid-cols-2">
            <Card className="p-5">
              <h2 className="text-[15px] font-semibold text-stone-900">{labelTh('PACK_RECIPIENT')}</h2>
              <p className="mt-3 text-[13px] text-stone-800">{session.recipient.name}</p>
              <p className="font-mono text-[13px] text-stone-800">{session.recipient.phone}</p>
              <p className="mt-2 whitespace-pre-wrap text-[13px] text-stone-700">
                {session.recipient.address}
              </p>
              <p className="mt-2 text-[13px] text-stone-600">
                {session.recipient.province} {session.recipient.postcode}
              </p>
            </Card>
            <Card className="p-5">
              <div className="flex items-center justify-between gap-3">
                <h2 className="text-[15px] font-semibold text-stone-900">{labelTh('PACK_STATUS')}</h2>
                <StatusBadge value={session.fulfillment_status} kind="fulfillment" />
              </div>
              <form className="mt-4 space-y-3" onSubmit={(e) => void submitScan(e)}>
                <div>
                  <Label htmlFor="pack-scan">{labelTh('PACK_SCAN_LABEL')}</Label>
                  <Input
                    id="pack-scan"
                    ref={inputRef}
                    autoComplete="off"
                    value={scan}
                    disabled={busy || session.fulfillment_status !== 'PICKING'}
                    onChange={(e) => setScan(e.target.value)}
                    placeholder={labelTh('PACK_SCAN_PLACEHOLDER')}
                  />
                </div>
                <Button
                  type="submit"
                  variant="secondary"
                  disabled={busy || session.fulfillment_status !== 'PICKING'}
                >
                  <Barcode className="h-4 w-4" />
                  {labelTh('PACK_SCAN_BUTTON')}
                </Button>
              </form>
            </Card>
          </div>

          <Card className="mt-5 overflow-hidden">
            <table className="w-full text-left text-[13px]">
              <thead className="border-b border-stone-100 bg-stone-50 text-[12px] text-stone-600">
                <tr>
                  <th className="px-4 py-2">{labelTh('SKU_CODE')}</th>
                  <th className="px-4 py-2">{labelTh('PRODUCT_NAME')}</th>
                  <th className="px-4 py-2 text-right">{labelTh('PACK_PROGRESS')}</th>
                </tr>
              </thead>
              <tbody>
                {session.lines.map((line) => (
                  <tr key={line.line_id} className="border-b border-stone-50">
                    <td className="px-4 py-2 font-mono text-[12.5px]">{line.sku_code ?? '—'}</td>
                    <td className="px-4 py-2">{line.name}</td>
                    <td className="px-4 py-2 text-right tabular-nums">
                      {line.packed_qty}/{line.required_qty}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        </>
      ) : null}
    </PageContent>
  )
}
