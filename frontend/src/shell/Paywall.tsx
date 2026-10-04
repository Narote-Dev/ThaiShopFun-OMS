import { Button } from '../ui/Button'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'

type Props = {
  onLogout: () => void
}

export default function Paywall({ onLogout }: Props) {
  return (
    <PageContent className="max-w-lg">
      <p className="text-[11px] font-semibold uppercase tracking-wider text-brand-700">ThaiShopFun</p>
      <PageHeader className="mt-2" title="Membership needed" />
      <p className="mt-4 text-[13px] leading-relaxed text-stone-600">
        OMS is not available for this shop. The membership is suspended, expired, or does not
        include OMS. Renew it in ThaiShopFun, or contact the shop owner. Billing stays in
        ThaiShopFun.
      </p>
      <p className="mt-4 text-[13px]">
        <a href="https://seller.thaishopfun.example/membership" className="font-medium text-brand-700 hover:underline">
          TSF Seller Center
        </a>
      </p>
      <Button type="button" variant="secondary" className="mt-6" onClick={onLogout}>
        Log out
      </Button>
    </PageContent>
  )
}
