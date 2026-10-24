import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { ConfirmationDialog } from './ConfirmationDialog'

afterEach(cleanup)

it('confirms explicitly and uses danger styling for a destructive action', () => {
  const onConfirm = vi.fn()
  const onOpenChange = vi.fn()
  render(<ConfirmationDialog open title="ลบข้อมูล" description="ลบข้อมูลนี้?" danger onOpenChange={onOpenChange} onConfirm={onConfirm} />)
  const confirmButton = screen.getByRole('button', { name: 'ยืนยัน' })
  expect(confirmButton).toHaveClass('bg-red-600')
  fireEvent.click(confirmButton)
  expect(onOpenChange).toHaveBeenCalledWith(false)
  expect(onConfirm).toHaveBeenCalledTimes(1)
})

it.each(['escape', 'close', 'cancel'])('dismisses via %s without confirming', (action) => {
  const onConfirm = vi.fn()
  const onOpenChange = vi.fn()
  render(<ConfirmationDialog open title="ตรวจสอบ" description="ตรวจสอบอีกครั้ง?" onOpenChange={onOpenChange} onConfirm={onConfirm} />)
  if (action === 'escape') fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
  else fireEvent.click(screen.getByRole('button', { name: action === 'close' ? 'Close' : 'ยกเลิก' }))
  expect(onOpenChange).toHaveBeenCalledWith(false)
  expect(onConfirm).not.toHaveBeenCalled()
})
