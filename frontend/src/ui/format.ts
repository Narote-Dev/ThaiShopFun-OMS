const BANGKOK = 'Asia/Bangkok'

export function bangkokTodayIso(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: BANGKOK }).format(new Date())
}

export function formatMoney(
  amount: number | string,
  { decimals = 2 }: { decimals?: number } = {},
): string {
  const n = typeof amount === 'string' ? Number.parseFloat(amount) : amount
  if (!Number.isFinite(n)) return '—'
  return `฿${n.toLocaleString('th-TH', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  })}`
}

export function formatBangkokDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const today = bangkokTodayIso()
  const day = new Intl.DateTimeFormat('en-CA', { timeZone: BANGKOK }).format(date)
  const time = new Intl.DateTimeFormat('th-TH', {
    timeZone: BANGKOK,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
  if (day === today) return `วันนี้ ${time}`
  const datePart = new Intl.DateTimeFormat('th-TH', {
    timeZone: BANGKOK,
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
  return datePart
}

export function formatBangkokDate(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return new Intl.DateTimeFormat('th-TH', {
    timeZone: BANGKOK,
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}
