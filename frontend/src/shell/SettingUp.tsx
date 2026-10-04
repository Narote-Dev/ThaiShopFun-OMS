import { Button } from '../ui/Button'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'

type Props = {
  message: string
  onRetry: (() => void) | null
}

export default function SettingUp({ message, onRetry }: Props) {
  return (
    <PageContent className="max-w-lg">
      <p className="text-[11px] font-semibold uppercase tracking-wider text-brand-700">ThaiShopFun</p>
      <PageHeader className="mt-2" title="Setting up your shop" />
      <p role="status" className="mt-4 text-[13px] text-stone-600">
        {message}
      </p>
      {onRetry ? (
        <Button type="button" variant="primary" className="mt-6" onClick={onRetry}>
          Retry
        </Button>
      ) : null}
    </PageContent>
  )
}
