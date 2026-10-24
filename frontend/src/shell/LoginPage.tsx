import { Button } from '../ui/Button'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'

type Props = {
  error: string | null
  onSignIn: () => void
}

export default function LoginPage({ error, onSignIn }: Props) {
  return (
    <PageContent className="max-w-lg">
      <p className="text-[11px] font-semibold uppercase tracking-wider text-brand-700">ThaiShopFun</p>
      <PageHeader className="mt-2" title="ระบบจัดการออเดอร์" />
      <p className="mt-2 text-[13px] text-stone-600">เข้าสู่ระบบด้วย TSF เพื่อเปิดร้านค้าของคุณ</p>
      {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : null}
      <Button type="button" variant="primary" className="mt-6" onClick={onSignIn}>
        เข้าสู่ระบบ
      </Button>
    </PageContent>
  )
}
