type Props = {
  onLogout: () => void
}

export default function Paywall({ onLogout }: Props) {
  return (
    <main>
      <p className="eyebrow">ThaiShopFun</p>
      <h1>Membership needed</h1>
      <p>
        OMS is not available for this shop. The membership is suspended, expired, or does not
        include OMS. Renew it in ThaiShopFun, or contact the shop owner. Billing stays in
        ThaiShopFun.
      </p>
      <p>
        <a href="https://seller.thaishopfun.example/membership">TSF Seller Center</a>
      </p>
      <button type="button" onClick={onLogout}>
        Log out
      </button>
    </main>
  )
}
