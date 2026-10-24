import { useState } from 'react'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { Input } from '../ui/Input'
import { Label } from '../ui/Label'
import { catalogApi, messageFor, type Sku } from './api'

type Line = { key: number; code: string; qty: string }

let nextKey = 1

function linesOf(sku: Sku): Line[] {
  return (sku.components ?? []).map((c) => ({ key: nextKey++, code: c.sku_code, qty: String(c.qty) }))
}

/** Replaces the whole component list in one call (PUT /skus/{id}/components). */
export default function BundleEditor({
  sku,
  canWrite,
  onSaved,
}: {
  sku: Sku
  canWrite: boolean
  onSaved: (sku: Sku) => void
}) {
  const [lines, setLines] = useState<Line[]>(() => linesOf(sku))
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)

  function change(key: number, patch: Partial<Line>) {
    setLines((current) => current.map((line) => (line.key === key ? { ...line, ...patch } : line)))
  }

  async function save() {
    // Step 1: Validate locally for a quick message. The server re-checks every rule.
    const components = lines
      .filter((line) => line.code.trim() !== '')
      .map((line) => ({ component_sku_code: line.code.trim(), qty: Number(line.qty) }))
    if (components.some((c) => !Number.isInteger(c.qty) || c.qty < 1)) {
      setError('จำนวนต้องเป็นจำนวนเต็มอย่างน้อย 1')
      return
    }
    setBusy(true)
    setError('')
    setNotice('')
    try {
      const saved = await catalogApi.replaceComponents(sku.id, components)
      setLines(linesOf(saved))
      setNotice('บันทึกส่วนประกอบแล้ว')
      onSaved(saved)
    } catch (err) {
      setError(messageFor(err, 'บันทึกส่วนประกอบไม่สำเร็จ'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card aria-label="ส่วนประกอบสินค้าชุด">
      <div className="border-b border-stone-100 px-5 py-3.5">
        <h2 className="text-[14.5px] font-semibold text-stone-900">ส่วนประกอบสินค้าชุด</h2>
        <p className="mt-1 text-[12px] text-stone-500">ส่วนประกอบต้องเป็นสินค้าปกติ สต็อกสินค้าชุดคำนวณจากส่วนประกอบ</p>
      </div>
      <div className="space-y-4 p-5">
        {lines.length === 0 ? <p className="text-[13px] text-stone-600">ยังไม่มีส่วนประกอบ</p> : null}
        <ul className="space-y-4">
          {lines.map((line, index) => (
            <li key={line.key} className="flex flex-wrap items-end gap-4">
              <div className="min-w-[160px] flex-1">
                <Label htmlFor={`bundle-code-${line.key}`}>ส่วนประกอบ {index + 1} รหัส</Label>
                <Input
                  id={`bundle-code-${line.key}`}
                  value={line.code}
                  disabled={!canWrite}
                  onChange={(event) => change(line.key, { code: event.target.value })}
                />
              </div>
              <div className="w-24">
                <Label htmlFor={`bundle-qty-${line.key}`}>จำนวน</Label>
                <Input
                  id={`bundle-qty-${line.key}`}
                  type="number"
                  min={1}
                  value={line.qty}
                  disabled={!canWrite}
                  onChange={(event) => change(line.key, { qty: event.target.value })}
                />
              </div>
              {canWrite ? (
                <Button type="button" variant="ghost" size="sm" onClick={() => setLines(lines.filter((l) => l.key !== line.key))}>
                  นำออก
                </Button>
              ) : null}
            </li>
          ))}
        </ul>
        {error ? (
          <p role="alert" className="text-[13px] text-red-700">
            {error}
          </p>
        ) : null}
        {notice ? <p className="text-[13px] text-stone-600">{notice}</p> : null}
        {canWrite ? (
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="secondary" onClick={() => setLines([...lines, { key: nextKey++, code: '', qty: '1' }])}>
              เพิ่มส่วนประกอบ
            </Button>
            <Button type="button" variant="primary" disabled={busy} onClick={() => void save()}>
              บันทึกส่วนประกอบ
            </Button>
          </div>
        ) : null}
      </div>
    </Card>
  )
}
