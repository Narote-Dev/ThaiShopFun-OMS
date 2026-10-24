import type { ReactNode } from 'react'
import { Button } from './Button'
import { Dialog } from './Dialog'

export function ConfirmationDialog({
  open,
  onOpenChange,
  title,
  description,
  onConfirm,
  danger = false,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: ReactNode
  description: ReactNode
  onConfirm: () => void
  danger?: boolean
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={title} description={description}>
      <div className="flex justify-end gap-2">
        <Button type="button" autoFocus onClick={() => onOpenChange(false)}>
          ยกเลิก
        </Button>
        <Button
          type="button"
          variant={danger ? 'danger' : 'primary'}
          onClick={() => {
            onOpenChange(false)
            onConfirm()
          }}
        >
          ยืนยัน
        </Button>
      </div>
    </Dialog>
  )
}
