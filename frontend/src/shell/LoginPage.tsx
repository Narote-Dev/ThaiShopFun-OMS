type Props = {
  error: string | null
  onSignIn: () => void
}

export default function LoginPage({ error, onSignIn }: Props) {
  return (
    <main>
      <p className="eyebrow">ThaiShopFun</p>
      <h1>OMS</h1>
      <p>Sign in with ThaiShopFun to open your shop.</p>
      {error ? <p role="alert">{error}</p> : null}
      <button type="button" onClick={onSignIn}>
        Sign in
      </button>
    </main>
  )
}
