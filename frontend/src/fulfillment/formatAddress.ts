/** Turn stored recipient address JSON (or plain text) into display lines. */
export function formatPackAddress(
  address: string | null | undefined,
  province: string | null | undefined,
  postcode: string | null | undefined,
): string[] {
  const lines: string[] = []
  const raw = address?.trim() ?? ''
  let prov = province?.trim() ?? ''
  let pc = postcode?.trim() ?? ''

  if (raw.startsWith('{')) {
    try {
      const obj = JSON.parse(raw) as Record<string, unknown>
      for (const key of ['line1', 'line2', 'subdistrict', 'district'] as const) {
        const value = obj[key]
        if (typeof value === 'string' && value.trim()) {
          lines.push(value.trim())
        }
      }
      if (typeof obj.province === 'string' && obj.province.trim()) {
        prov = obj.province.trim()
      }
      if (typeof obj.postcode === 'string' && obj.postcode.trim()) {
        pc = obj.postcode.trim()
      }
    } catch {
      if (raw) lines.push(raw)
    }
  } else if (raw) {
    lines.push(raw)
  }

  const locality = [prov, pc].filter(Boolean).join(' ')
  if (locality) {
    lines.push(locality)
  }
  return lines
}
