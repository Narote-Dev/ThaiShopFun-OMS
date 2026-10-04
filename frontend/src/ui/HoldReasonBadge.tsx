import { Badge } from './Badge'
import { holdReasonLabelTh, resolveStatus } from './status'

export function HoldReasonBadge({ reason, detail }: { reason: string; detail?: string | null }) {
  if (!reason || reason === 'NONE') return <span className="text-stone-300">—</span>
  const style = resolveStatus(reason, 'hold_reason')
  return (
    <div>
      <Badge variant={style.variant} className="normal-case">
        {holdReasonLabelTh(reason)}
      </Badge>
      {detail ? <div className="mt-1 font-mono text-[10.5px] text-stone-500">{detail}</div> : null}
    </div>
  )
}
