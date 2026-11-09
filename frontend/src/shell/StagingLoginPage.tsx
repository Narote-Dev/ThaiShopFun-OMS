import { useEffect, useState } from 'react'
import { fetchStagingDemoAccounts, stagingLogin } from '../auth/stagingSession'
import { labelTh } from '../ui/labels'
import { Button } from '../ui/Button'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'

type Account = { login_id: string; shop_name: string; shop_role: string }

type Props = {
  error: string | null
  onSignedIn: () => void
}

export default function StagingLoginPage({ error, onSignedIn }: Props) {
  const [accounts, setAccounts] = useState<Account[]>([])
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const displayError = formError ?? error

  useEffect(() => {
    void fetchStagingDemoAccounts().then((rows) => {
      setAccounts(rows)
      if (rows.length > 0) {
        setLoginId((prev) => (prev ? prev : rows[0].login_id))
      }
    })
  }, [])

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    setFormError(null)
    try {
      await stagingLogin(loginId, password)
      onSignedIn()
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'เข้าสู่ระบบไม่สำเร็จ')
    } finally {
      setBusy(false)
    }
  }

  return (
    <PageContent className="max-w-lg">
      <p className="text-[11px] font-semibold uppercase tracking-wider text-brand-700">ThaiShopFun OMS</p>
      <PageHeader className="mt-2" title="เข้าสู่ระบบทดลอง" />
      <p className="mt-2 text-[13px] text-stone-600">
        บัญชีทดลองที่ทีมจัดให้เท่านั้น ไม่มีการสมัครสมาชิกใหม่
      </p>
      <form className="mt-6 space-y-4" onSubmit={submit}>
        <label className="block text-[13px]">
          <span className="font-medium text-stone-800">บัญชีทดลอง</span>
          <select
            className="mt-1 w-full rounded-md border border-stone-300 bg-white px-3 py-2 text-[13px]"
            value={loginId}
            onChange={(e) => setLoginId(e.target.value)}
            required
          >
            {accounts.map((row) => (
              <option key={row.login_id} value={row.login_id}>
                {row.shop_name} ({labelTh(row.shop_role)})
              </option>
            ))}
          </select>
        </label>
        <label className="block text-[13px]">
          <span className="font-medium text-stone-800">รหัสผ่านทดลอง</span>
          <input
            type="password"
            autoComplete="current-password"
            className="mt-1 w-full rounded-md border border-stone-300 px-3 py-2 text-[13px]"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        {displayError ? (
          <p role="alert" className="text-[13px] text-red-700">
            {displayError}
          </p>
        ) : null}
        <Button type="submit" variant="primary" disabled={busy} className="w-full">
          {busy ? 'กำลังเข้าสู่ระบบ…' : 'เข้าสู่ระบบ'}
        </Button>
      </form>
    </PageContent>
  )
}
