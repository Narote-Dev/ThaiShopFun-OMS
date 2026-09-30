type Props = {
  message: string
  onRetry: (() => void) | null
}

export default function SettingUp({ message, onRetry }: Props) {
  return (
    <main>
      <p className="eyebrow">ThaiShopFun</p>
      <h1>Setting up your shop</h1>
      <p role="status">{message}</p>
      {onRetry ? (
        <button type="button" onClick={onRetry}>
          Retry
        </button>
      ) : null}
    </main>
  )
}
