import { Badge } from './Badge'
import { holdReasonLabelTh, resolveStatus, type StatusKind } from './status'

export function StatusBadge({ value, kind }: { value: string; kind: StatusKind }) {
  const style = resolveStatus(value, kind)
  if (style.label === '—') return <span className="text-stone-400">—</span>
  const label =
    kind === 'hold_reason' && value !== 'NONE' ? holdReasonLabelTh(value) : (style.label ?? value)
  return (
    <Badge variant={style.variant} strikethrough={style.strikethrough}>
      {label}
    </Badge>
  )
}
