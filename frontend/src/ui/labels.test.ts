import { describe, expect, it } from 'vitest'
import { importErrorTh, labelTh, messageTh } from './labels'
import { ALL_STATUS_VALUES, resolveOrderDisplayStatus, resolveStatus } from './status'

describe('Thai UI labels', () => {
  it('translates every known status while retaining channel TSF and status colours', () => {
    for (const { kind, value } of ALL_STATUS_VALUES) {
      const style = resolveStatus(value, kind)
      if (value === 'TSF') expect(style.label).toBe('TSF')
      else expect(style.label).not.toMatch(/[A-Za-z]/)
    }
    expect(resolveStatus('READY_TO_PICK', 'fulfillment')).toEqual({ variant: 'success', label: 'รอหยิบ' })
    expect(resolveStatus('COD', 'payment_method')).toEqual({ variant: 'violet', label: 'เก็บเงินปลายทาง' })
    expect(resolveOrderDisplayStatus({ order_status: 'ACTIVE', fulfillment_status: 'READY_TO_PICK', hold_reason: 'CHANNEL_CANCEL_PENDING' }))
      .toEqual({ variant: 'danger', label: 'พักออเดอร์' })
  })

  it('translates timeline dimensions, values, actors and reasons centrally', () => {
    expect(['HOLD', 'NONE', 'CHANNEL_CANCEL_PENDING', 'cancel requested', 'USER'].map((value) => labelTh(value)))
      .toEqual(['การพักออเดอร์', 'ไม่พักออเดอร์', 'รอยกเลิกจากช่องทาง', 'ส่งคำขอยกเลิกแล้ว', 'ผู้ใช้'])
    expect(labelTh('MANUAL', 'mapping_source')).toBe('จับคู่ด้วยมือ')
    expect(labelTh('MANUAL')).toBe('พักด้วยมือ')
    expect(labelTh('FUTURE_STATUS')).toBe('ไม่ระบุ')
  })

  it('translates errors while preserving the offending SKU and stock quantities', () => {
    expect(importErrorTh('NESTED_BUNDLE: component SET-1 is a bundle')).toBe('ส่วนประกอบ SET-1 เป็นสินค้าชุด จึงซ้อนกันไม่ได้')
    expect(messageTh('on_hand 5 + -2 would be below reserved 4', 'ผิดพลาด')).toBe('คงเหลือ 5 ปรับ -2 จะต่ำกว่ายอดจอง 4')
    expect(messageTh('Network error', 'โหลดไม่สำเร็จ')).toBe('โหลดไม่สำเร็จ')
  })
})
