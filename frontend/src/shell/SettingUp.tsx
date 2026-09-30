export default function SettingUp({ message }: { message: string | null }) {
  return (
    <main>
      <p className="eyebrow">ThaiShopFun</p>
      <h1>Setting up your shop</h1>
      <p role="status">Your shop is being set up. Retrying…</p>
      {message ? <p>{message}</p> : null}
    </main>
  )
}
