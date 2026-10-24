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
      <PageHeader className="mt-2" title="ต้องต่ออายุสมาชิก" />
      <p className="mt-4 text-[13px] leading-relaxed text-stone-600">
        ร้านค้านี้ยังใช้ระบบจัดการออเดอร์ไม่ได้ เนื่องจากสมาชิกถูกระงับ หมดอายุ หรือแพ็กเกจไม่ครอบคลุมระบบนี้ กรุณาต่ออายุสมาชิกใน TSF หรือติดต่อเจ้าของร้าน การชำระค่าสมาชิกดำเนินการใน TSF
      </p>
      <p className="mt-4 text-[13px]">
        <a href="https://seller.thaishopfun.example/membership" className="font-medium text-brand-700 hover:underline">
          ศูนย์ผู้ขาย TSF
        </a>
      </p>
      <Button type="button" variant="secondary" className="mt-6" onClick={onLogout}>
        ออกจากระบบ
      </Button>
    </PageContent>
  )
}
