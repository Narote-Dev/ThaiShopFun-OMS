import { useState } from 'react'
import { AlertBanner } from '../ui/Alert'
import { Button } from '../ui/Button'
import { Dialog } from '../ui/Dialog'
import { Label } from '../ui/Label'
import { Select } from '../ui/Select'
import { Textarea } from '../ui/Textarea'

export function CancelRequestDialog({ onClose, onSubmit, busy, error }: {
  onClose: () => void
  onSubmit: (reason: string) => void
  busy: boolean
  error: string
}) {
  const [preset, setPreset] = useState('')
  const [other, setOther] = useState('')
  const [submitted, setSubmitted] = useState(false)
  const reason = preset === 'อื่นๆ' ? other.trim() : preset
  const validation = !reason
    ? 'กรุณาระบุเหตุผล'
    : reason.length > 500 ? 'เหตุผลต้องไม่เกิน 500 ตัวอักษร' : ''
  const showValidation = submitted || (preset === 'อื่นๆ' && other.trim().length > 500)

  return (
    <Dialog
      open
      onOpenChange={(open) => { if (!open && !busy) onClose() }}
      title="ขอยกเลิกออเดอร์"
      description="OMS จะส่งคำขอไป TSF — TSF เป็นผู้ตัดสิน"
    >
      <form
        className="space-y-4"
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          setSubmitted(true)
          if (!validation && !busy) onSubmit(reason)
        }}
      >
        <div>
          <Label htmlFor="cancel-reason">เหตุผล</Label>
          <Select id="cancel-reason" value={preset} disabled={busy}
            aria-invalid={showValidation && !!validation} aria-describedby="cancel-validation"
            onChange={(event) => setPreset(event.target.value)}>
            <option value="">เลือกเหตุผล</option>
            {['ลูกค้าขอยกเลิก', 'สินค้าหมด', 'ที่อยู่ไม่ถูกต้อง', 'อื่นๆ'].map((value) => <option key={value}>{value}</option>)}
          </Select>
        </div>
        {preset === 'อื่นๆ' ? <div>
          <Label htmlFor="cancel-other">เหตุผลอื่นๆ</Label>
          <Textarea id="cancel-other" value={other} disabled={busy}
            aria-invalid={showValidation && !!validation} aria-describedby="cancel-validation"
            onChange={(event) => setOther(event.target.value)} />
        </div> : null}
        <div id="cancel-validation">
          {showValidation && validation ? <AlertBanner variant="danger" title={validation} /> : null}
        </div>
        {error ? <AlertBanner variant="danger" title={error} /> : null}
        <div className="flex justify-end gap-2">
          <Button type="button" disabled={busy} onClick={onClose}>ยกเลิก</Button>
          <Button type="submit" variant="danger" disabled={busy}>ส่งคำขอยกเลิก</Button>
        </div>
      </form>
    </Dialog>
  )
}
