// UI labels only: keep API values and identifiers unchanged.
const LABELS: Record<string, string> = {
  CONTROL: 'คุมเฉพาะที่เลือก',
  OBSERVE: 'สังเกตการณ์',
  SHADOW: 'ทดลองควบคุม',
  STOCK_CONTROL: 'คุมสต็อกโดย OMS',
  STOCK_CONTROL_FILTER_ON: 'ที่เปิดคุมสต็อก',
  STOCK_CONTROL_FILTER_OFF: 'ที่ปิดคุมสต็อก',
  STOCK_CONTROL_ENABLED: 'คุมอยู่',
  STOCK_CONTROL_DISABLED: 'ไม่ได้คุม',
  STOCK_CONTROL_ON: 'เปิดคุมสต็อก',
  STOCK_CONTROL_OFF: 'ปิดคุมสต็อก',
  LABEL_READY: 'ใบปะหน้าพร้อมแล้ว',
  FAILED: 'ไม่สำเร็จ',
  RETURNED_TO_SENDER: 'ตีกลับผู้ส่ง',
  'listing not mapped': 'รายการขายยังไม่ได้จับคู่',
  WAREHOUSE_IN_USE: 'คลังสินค้านี้ถูกใช้งานอยู่',
  'stock.updated': 'ปรับปรุงสต็อก',
  'inventory.updated': 'ปรับปรุงสินค้าคงคลัง',
  'Invalid or expired token': 'เซสชันไม่ถูกต้องหรือหมดอายุ กรุณาเข้าสู่ระบบอีกครั้ง',
  'TSF cancel': 'ยกเลิกจาก TSF',
  'TSF paid': 'ชำระเงินผ่าน TSF แล้ว',
  'stock ready': 'สต็อกพร้อมแล้ว',
  intake: 'รับออเดอร์เข้าระบบ',
  BUNDLE_WITHOUT_COMPONENTS: 'สินค้าชุดยังไม่มีส่วนประกอบ',
  BUNDLE_REQUIRED: 'ต้องระบุว่าเป็นสินค้าชุดก่อนเพิ่มส่วนประกอบ',
  BUNDLE_NOT_STOCKABLE: 'สินค้าชุดไม่มีสต็อกของตัวเอง',
  AUTO_MATCH: 'จับคู่อัตโนมัติ',
  SKU_CODE: 'จับคู่รหัสสินค้า',
  AUTO_EXACT_MATCH: 'จับคู่อัตโนมัติ',
  OUTBOX: 'คิวส่งเหตุการณ์',
  ACCEPTED: 'ยอมรับแล้ว',
  REJECTED: 'ปฏิเสธแล้ว',
  REQUESTED: 'ส่งคำขอแล้ว',
  CANCEL_REQUESTED: 'ส่งคำขอยกเลิกแล้ว',
  CONFIRMED: 'ยืนยันแล้ว',
  IN_TRANSIT: 'ระหว่างจัดส่ง',

  'READY_TO_PICK': 'รอหยิบ',
  'PICKING': 'กำลังหยิบ',
  'PACKED': 'แพ็กแล้ว',
  'SHIPPED': 'จัดส่งแล้ว',
  'DELIVERED': 'ส่งถึงแล้ว',
  'UNFULFILLED': 'ยังไม่จัดส่ง',
  'ACTIVE': 'ใช้งาน',
  'CANCELLED': 'ยกเลิกแล้ว',
  'COMPLETED': 'เสร็จสิ้น',
  'PENDING': 'รอดำเนินการ',
  'PAID': 'ชำระแล้ว',
  'COD_PENDING': 'รอเก็บเงินปลายทาง',
  'PARTIALLY_REFUNDED': 'คืนเงินบางส่วน',
  'REFUNDED': 'คืนเงินแล้ว',
  'NONE': 'ไม่พักออเดอร์',
  'SKU_NOT_MAPPED': 'รหัสสินค้ายังไม่ได้จับคู่',
  'OUT_OF_STOCK': 'สต็อกไม่พอ',
  'ADDRESS_PROBLEM': 'ที่อยู่มีปัญหา',
  'PAYMENT_MISMATCH': 'ยอดชำระไม่ตรง',
  'CHANNEL_CANCEL_PENDING': 'รอยกเลิกจากช่องทาง',
  'MANUAL': 'พักด้วยมือ',
  'ON HOLD': 'พักออเดอร์',
  'COD': 'เก็บเงินปลายทาง',
  'PREPAID': 'ชำระล่วงหน้า',
  'TSF': 'TSF',
  'SHOPEE': 'ช้อปปี้',
  'LAZADA': 'ลาซาด้า',
  'TIKTOK': 'ติ๊กต็อก',
  'DRAFT': 'ฉบับร่าง',
  'POSTED': 'ลงบัญชีแล้ว',
  'VOID': 'ยกเลิกเอกสาร',
  'INACTIVE': 'ไม่ใช้งาน',
  'DEAD': 'ส่งไม่สำเร็จ',
  'SENT': 'ส่งแล้ว',
  'OWNER': 'เจ้าของร้าน',
  'ADMIN': 'ผู้ดูแลระบบ',
  'STAFF': 'พนักงาน',
  'USER': 'ผู้ใช้',
  'SYSTEM': 'ระบบ',
  'PRO': 'โปร',
  'BASIC': 'พื้นฐาน',
  'FREE': 'ฟรี',
  'ENTERPRISE': 'องค์กร',
  'GRACE': 'รอต่ออายุ',
  'EXPIRED': 'หมดอายุ',
  'SUSPENDED': 'ระงับ',
  'CONNECTED': 'เชื่อมต่อแล้ว',
  'DISCONNECTED': 'ยังไม่เชื่อมต่อ',
  'RESERVED': 'จองแล้ว',
  'RELEASED': 'ปล่อยแล้ว',
  'CONSUMED': 'ใช้แล้ว',
  'FULFILLED': 'จัดส่งแล้ว',
  'AUTO': 'อัตโนมัติ',
  'AUTO_EXACT': 'จับคู่อัตโนมัติ',
  'AUTO_SKU_CODE': 'จับคู่รหัสสินค้าอัตโนมัติ',
  'ORDER': 'ออเดอร์',
  'ORDER_STATUS': 'สถานะออเดอร์',
  'PAYMENT': 'การชำระเงิน',
  'PAYMENT_STATUS': 'สถานะการชำระเงิน',
  'FULFILLMENT': 'การจัดส่ง',
  'FULFILLMENT_STATUS': 'สถานะการจัดส่ง',
  'HOLD': 'การพักออเดอร์',
  'HOLD_REASON': 'เหตุผลที่พัก',
  'OPENING': 'ยอดยกมา',
  'RECEIVE': 'รับเข้า',
  'ADJUSTMENT': 'ปรับสต็อก',
  'COUNT': 'ตรวจนับสต็อก',
  'WRITE_OFF': 'ตัดสต็อก',
  'DAMAGED': 'เสียหาย',
  'LOST': 'สูญหาย',
  'FOUND': 'พบสินค้า',
  'DATA_ENTRY': 'แก้ไขข้อมูล',
  'OTHER': 'อื่นๆ',
  'OPENING_BALANCE': 'ยอดยกมา',
  'ADJUST_IN': 'ปรับเพิ่ม',
  'ADJUST_OUT': 'ปรับลด',
  'COUNT_CORRECTION': 'ปรับยอดตรวจนับ',
  'DAMAGE_WRITE_OFF': 'ตัดสินค้าเสียหาย',
  'RETURN_RESTOCK': 'คืนเข้าสต็อก',
  'SHIP': 'ตัดเพื่อจัดส่ง',
  'RESERVE': 'จองสต็อก',
  'RELEASE': 'ปล่อยสต็อก',
  'UNPACK': 'แยกสินค้าชุด',
  'cancel requested': 'ส่งคำขอยกเลิกแล้ว',
  'cancel confirmed': 'ยืนยันยกเลิกแล้ว',
  'cancel rejected': 'ปฏิเสธคำขอยกเลิก',
  'product_name': 'ชื่อสินค้า',
  'sku_code': 'รหัสสินค้า',
  'sku_name': 'ชื่อรหัสสินค้า',
  'barcode': 'บาร์โค้ด',
  'weight_g': 'น้ำหนัก (กรัม)',
  'is_bundle': 'เป็นสินค้าชุด',
  'components': 'ส่วนประกอบ',
  'ORDER_CREATED': 'สร้างออเดอร์แล้ว',
  'ORDER_CANCELLED': 'ยกเลิกออเดอร์แล้ว',
  'STOCK_CHANGED': 'สต็อกเปลี่ยนแปลง',
  'SKU_CODE_EXISTS': 'รหัสสินค้านี้มีอยู่แล้ว',
  'NESTED_BUNDLE': 'สินค้าชุดซ้อนกันไม่ได้',
  'BELOW_RESERVED': 'ยอดสต็อกต่ำกว่ายอดจอง',
  'IMPORT_INVALID': 'ข้อมูลนำเข้าไม่ถูกต้อง',
  'is required': 'จำเป็นต้องระบุ',
  'unknown column': 'ไม่รู้จักคอลัมน์นี้',
  'duplicate column': 'คอลัมน์ซ้ำ',
  'column is required': 'ต้องมีคอลัมน์นี้',
  'must be a whole number': 'ต้องเป็นจำนวนเต็ม',
  'must be true or false': 'ต้องระบุค่าจริงหรือเท็จ',
  'header row is required': 'ต้องมีแถวหัวตาราง',
  'the file has no data rows': 'ไฟล์ไม่มีแถวข้อมูล'
}

export function labelTh(value: string | null | undefined, context?: 'mapping_source'): string {
  if (context === 'mapping_source' && value === 'MANUAL') return 'จับคู่ด้วยมือ'
  if (!value) return '—'
  return LABELS[value] ?? LABELS[value.toUpperCase()] ?? (/^[^a-zA-Z]*$/.test(value) ? value : 'ไม่ระบุ')
}

/** Preserve Thai service messages; never expose untranslated server diagnostics. */
export function messageTh(value: string | null | undefined, fallback: string): string {
  if (!value) return fallback
  if (LABELS[value]) return LABELS[value]
  const reserved = /^on_hand ([-\d]+) \+ ([-\d]+) would be below reserved (\d+)$/.exec(value)
  if (reserved) return `คงเหลือ ${reserved[1]} ปรับ ${reserved[2]} จะต่ำกว่ายอดจอง ${reserved[3]}`
  return /[ก-๙]/.test(value) ? value : fallback
}

/** Translate row validation while retaining the offending SKU code. */
export function importErrorTh(value: string): string {
  if (LABELS[value]) return LABELS[value]
  const nested = /^NESTED_BUNDLE: component (.+) is a bundle$/.exec(value)
  if (nested) return `ส่วนประกอบ ${nested[1]} เป็นสินค้าชุด จึงซ้อนกันไม่ได้`
  const unknown = /^unknown sku_code (.+)$/.exec(value)
  if (unknown) return `ไม่พบรหัสสินค้า ${unknown[1]}`
  const duplicate = /^duplicate sku_code \(first on row (\d+)\)$/.exec(value)
  if (duplicate) return `รหัสสินค้าซ้ำกับแถว ${duplicate[1]}`
  const fields = /^expected (\d+) fields, found (\d+)$/.exec(value)
  if (fields) return `ต้องมี ${fields[1]} ช่อง แต่พบ ${fields[2]} ช่อง`
  const code = value.split(':')[0]
  return messageTh(value, LABELS[code] ?? 'ข้อมูลไม่ถูกต้อง กรุณาตรวจสอบรูปแบบและค่าที่ระบุ')
}

/** Hold detail is either a known code (translated) or an identifier such as a SKU code (kept). */
export function holdDetailTh(value: string | null | undefined): string {
  if (!value) return '—'
  return LABELS[value] ?? value
}
