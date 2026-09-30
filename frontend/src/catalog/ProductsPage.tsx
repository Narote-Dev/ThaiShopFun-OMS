import { useEffect, useState, type FormEvent } from 'react'
import { catalogApi, messageFor, type Product } from './api'

export default function ProductsPage({ canWrite }: { canWrite: boolean }) {
  const [products, setProducts] = useState<Product[]>([])
  const [names, setNames] = useState<Record<string, string>>({})
  const [newName, setNewName] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [version, setVersion] = useState(0)

  useEffect(() => {
    let active = true
    catalogApi
      .listProducts()
      .then((page) => {
        if (!active) return
        setProducts(page.items)
        setNames(Object.fromEntries(page.items.map((p) => [p.id, p.name])))
      })
      .catch((err: unknown) => {
        if (active) setError(messageFor(err, 'Could not load products'))
      })
    return () => {
      active = false
    }
  }, [version])

  // Step 1: Every write reloads the list so counts and statuses stay server-true.
  async function run(action: () => Promise<unknown>, fallback: string) {
    setBusy(true)
    setError('')
    try {
      await action()
      setVersion((v) => v + 1)
      return true
    } catch (err) {
      setError(messageFor(err, fallback))
      return false
    } finally {
      setBusy(false)
    }
  }

  async function create(event: FormEvent) {
    event.preventDefault()
    if (await run(() => catalogApi.createProduct(newName.trim()), 'Could not create the product')) setNewName('')
  }

  return (
    <main className="wide">
      <h1>Products</h1>
      {canWrite ? (
        <form className="toolbar" onSubmit={create}>
          <label>
            New product name
            <input value={newName} onChange={(event) => setNewName(event.target.value)} />
          </label>
          <button type="submit" disabled={busy || newName.trim() === ''}>
            Add product
          </button>
        </form>
      ) : null}
      {error ? <p role="alert">{error}</p> : null}
      <table>
        <thead>
          <tr>
            <th>Name</th>
            <th>Status</th>
            <th>SKUs</th>
            {canWrite ? <th>Actions</th> : null}
          </tr>
        </thead>
        <tbody>
          {products.map((product) => (
            <tr key={product.id}>
              <td>
                {canWrite ? (
                  <input
                    aria-label={`Name of ${product.name}`}
                    value={names[product.id] ?? ''}
                    onChange={(event) => setNames({ ...names, [product.id]: event.target.value })}
                  />
                ) : (
                  product.name
                )}
              </td>
              <td>{product.status}</td>
              <td>{product.sku_count}</td>
              {canWrite ? (
                <td>
                  <button
                    type="button"
                    disabled={busy || (names[product.id] ?? '').trim() === product.name}
                    onClick={() =>
                      void run(
                        () => catalogApi.updateProduct(product.id, names[product.id].trim(), product.status),
                        'Could not rename the product',
                      )
                    }
                  >
                    Rename
                  </button>
                  <button
                    type="button"
                    disabled={busy || product.status === 'INACTIVE'}
                    onClick={() => void run(() => catalogApi.archiveProduct(product.id), 'Could not archive')}
                  >
                    Archive
                  </button>
                </td>
              ) : null}
            </tr>
          ))}
        </tbody>
      </table>
    </main>
  )
}
