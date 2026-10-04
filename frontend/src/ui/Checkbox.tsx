import * as CheckboxPrimitive from '@radix-ui/react-checkbox'
import { Check } from 'lucide-react'
import { cn } from './cn'

export function Checkbox({
  checked,
  onCheckedChange,
  'aria-label': ariaLabel,
  className,
}: {
  checked?: boolean
  onCheckedChange?: (checked: boolean) => void
  'aria-label'?: string
  className?: string
}) {
  return (
    <CheckboxPrimitive.Root
      checked={checked}
      onCheckedChange={(v) => onCheckedChange?.(v === true)}
      aria-label={ariaLabel}
      className={cn(
        'flex h-4 w-4 items-center justify-center rounded border border-stone-300 bg-white text-white data-[state=checked]:border-brand-600 data-[state=checked]:bg-brand-600',
        className,
      )}
    >
      <CheckboxPrimitive.Indicator>
        <Check className="h-3 w-3" strokeWidth={3} />
      </CheckboxPrimitive.Indicator>
    </CheckboxPrimitive.Root>
  )
}
