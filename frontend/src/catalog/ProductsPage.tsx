import { useEffect, useState, type FormEvent } from 'react'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import {
  DataTable,
  DataTableCell,
  DataTableHead,
  DataTableRow,
  DataTableTh,
} from '../ui/DataTable'
import { Input } from '../ui/Input'
import { Label } from '../ui/Label'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'
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
    <PageContent wide>
      <PageHeader
        title={
          <>
            <span aria-hidden="true">สินค้า (Products)</span>
            <span className="sr-only">Products</span>
          </>
        }
      />
      {canWrite ? (
        <Card className="mt-4">
          <form className="flex flex-wrap items-end gap-4 p-4" onSubmit={create}>
            <div className="min-w-[200px] flex-1">
              <Label htmlFor="products-new-name">New product name</Label>
              <Input id="products-new-name" value={newName} onChange={(event) => setNewName(event.target.value)} />
            </div>
            <Button type="submit" variant="primary" disabled={busy || newName.trim() === ''}>
              Add product
            </Button>
          </form>
        </Card>
      ) : null}
      {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : null}
      <div className="mt-4">
        <DataTable aria-label="Products">
          <DataTableHead>
            <tr>
              <DataTableTh>Name</DataTableTh>
              <DataTableTh>Status</DataTableTh>
              <DataTableTh>SKUs</DataTableTh>
              {canWrite ? <DataTableTh>Actions</DataTableTh> : null}
            </tr>
          </DataTableHead>
          <tbody>
            {products.map((product) => (
              <DataTableRow key={product.id}>
                <DataTableCell>
                  {canWrite ? (
                    <Input
                      aria-label={`Name of ${product.name}`}
                      value={names[product.id] ?? ''}
                      onChange={(event) => setNames({ ...names, [product.id]: event.target.value })}
                      className="mt-0"
                    />
                  ) : (
                    product.name
                  )}
                </DataTableCell>
                <DataTableCell>{product.status}</DataTableCell>
                <DataTableCell>{product.sku_count}</DataTableCell>
                {canWrite ? (
                  <DataTableCell>
                    <div className="flex flex-wrap gap-2">
                      <Button
                        type="button"
                        size="sm"
                        disabled={busy || (names[product.id] ?? '').trim() === product.name}
                        onClick={() =>
                          void run(
                            () => catalogApi.updateProduct(product.id, names[product.id].trim(), product.status),
                            'Could not rename the product',
                          )
                        }
                      >
                        Rename
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        disabled={busy || product.status === 'INACTIVE'}
                        onClick={() => void run(() => catalogApi.archiveProduct(product.id), 'Could not archive')}
                      >
                        Archive
                      </Button>
                    </div>
                  </DataTableCell>
                ) : null}
              </DataTableRow>
            ))}
          </tbody>
        </DataTable>
      </div>
    </PageContent>
  )
}
