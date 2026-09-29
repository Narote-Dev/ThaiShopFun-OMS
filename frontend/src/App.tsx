import { useEffect, useState } from 'react'
import OutboxAdminPage from './outbox/OutboxAdminPage'

export default function App() {
  const [hash, setHash] = useState(window.location.hash)

  useEffect(() => {
    const onHash = () => setHash(window.location.hash)
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])

  if (hash === '#/admin/outbox') {
    return <OutboxAdminPage />
  }

  return (
    <main>
      <p className="eyebrow">ThaiShopFun</p>
      <h1>OMS</h1>
      <p>Order management shell. Sign-in arrives in a later task.</p>
      <p>
        <a href="#/admin/outbox">Dead outbox</a>
      </p>
    </main>
  )
}
