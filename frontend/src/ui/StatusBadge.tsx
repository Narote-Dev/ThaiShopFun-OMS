import { Badge } from './Badge'
import { resolveStatus, type StatusKind } from './status'

export function StatusBadge({ value, kind }: { value: string; kind: StatusKind }) {
  const style = resolveStatus(value, kind)
  if (style.label === '—') return <span className="text-stone-400">—</span>
  return (
    <Badge variant={style.variant} strikethrough={style.strikethrough}>
      {style.label ?? value}
    </Badge>
  )
}
