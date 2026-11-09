import { useState } from 'react'
import { stagingLogin } from '../auth/stagingSession'
import { labelTh } from '../ui/labels'
import { Button } from '../ui/Button'
import { Input } from '../ui/Input'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'

type Props = {
  error: string | null
  onSignedIn: () => void
}

export default function StagingLoginPage({ error, onSignedIn }: Props) {
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const displayError = formError ?? error

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    setFormError(null)
    try {
      await stagingLogin(loginId, password)
      onSignedIn()
    } catch (err) {
      setFormError(err instanceof Error ? err.message : labelTh('STAGING_LOGIN_ERROR'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <PageContent className="max-w-lg">
      <p className="text-[11px] font-semibold uppercase tracking-wider text-brand-700">
        {labelTh('STAGING_BRAND')}
      </p>
      <PageHeader className="mt-2" title={labelTh('STAGING_LOGIN_TITLE')} />
      <p className="mt-2 text-[13px] text-stone-600">{labelTh('STAGING_LOGIN_HINT')}</p>
      <form className="mt-6 space-y-4" onSubmit={submit}>
        <label className="block text-[13px]">
          <span className="font-medium text-stone-800">{labelTh('STAGING_LOGIN_ID')}</span>
          <Input
            autoComplete="username"
            value={loginId}
            onChange={(e) => setLoginId(e.target.value)}
            required
          />
        </label>
        <label className="block text-[13px]">
          <span className="font-medium text-stone-800">{labelTh('STAGING_LOGIN_PASSWORD')}</span>
          <Input
            type="password"
            autoComplete="current-password"
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
          {busy ? labelTh('STAGING_LOGIN_SUBMITTING') : labelTh('STAGING_LOGIN_SUBMIT')}
        </Button>
      </form>
    </PageContent>
  )
}
