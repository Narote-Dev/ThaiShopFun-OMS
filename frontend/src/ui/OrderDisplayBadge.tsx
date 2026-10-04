import { Badge } from './Badge'
import { resolveOrderDisplayStatus, type OrderDisplayInput } from './status'

export function OrderDisplayBadge(order: OrderDisplayInput) {
  const style = resolveOrderDisplayStatus(order)
  return (
    <Badge variant={style.variant} strikethrough={style.strikethrough}>
      {style.label}
    </Badge>
  )
}
