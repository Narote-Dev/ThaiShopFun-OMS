function formatExpiry(iso: string | null): string {
  if (!iso) return 'วันต่ออายุ'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return date.toISOString().slice(0, 10)
}

export default function GraceBanner({ expiresAt }: { expiresAt: string | null }) {
  return (
    <p
      className="border-b border-amber-200 bg-amber-50 px-6 py-2 text-[13px] text-amber-950"
      role="status"
    >
      ร้านค้านี้อ่านได้อย่างเดียวจนถึง {formatExpiry(expiresAt)} ต่ออายุสมาชิกเพื่อแก้ไขข้อมูล
    </p>
  )
}
