import { useEffect, useState, type FormEvent } from 'react'
import BundleEditor from './BundleEditor'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { Input } from '../ui/Input'
import { Label } from '../ui/Label'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'
import { Select } from '../ui/Select'
import { catalogApi, messageFor, type Product, type Sku, type SkuInput } from './api'

const NEW_PRODUCT = '__new__'

type Draft = {
  productId: string
  productName: string
  code: string
  name: string
  barcode: string
  weight: string
  bundle: boolean
}

const emptyDraft: Draft = {
  productId: NEW_PRODUCT,
  productName: '',
  code: '',
  name: '',
  barcode: '',
  weight: '',
  bundle: false,
}

function draftOf(sku: Sku): Draft {
  return {
    productId: sku.product_id,
    productName: '',
    code: sku.sku_code,
    name: sku.name,
    barcode: sku.barcode ?? '',
    weight: sku.weight_g === null ? '' : String(sku.weight_g),
    bundle: sku.is_bundle,
  }
}

/** Create (id = null) or edit one SKU. A new product can be created in the same call. */
export default function SkuFormPage({ id, canWrite }: { id: string | null; canWrite: boolean }) {
  const [products, setProducts] = useState<Product[]>([])
  const [sku, setSku] = useState<Sku | null>(null)
  const [draft, setDraft] = useState<Draft>(emptyDraft)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)

  // Step 1: Load the product list and, when editing, the SKU with its components.
  useEffect(() => {
    let active = true
    Promise.all([catalogApi.listProducts(), id ? catalogApi.getSku(id) : Promise.resolve(null)])
      .then(([page, loaded]) => {
        if (!active) return
        setProducts(page.items)
        if (loaded) {
          setSku(loaded)
          setDraft(draftOf(loaded))
        } else if (page.items.length > 0) {
          setDraft((current) => ({ ...current, productId: page.items[0].id }))
        }
      })
      .catch((err: unknown) => {
        if (active) setError(messageFor(err, 'Could not load the SKU'))
      })
    return () => {
      active = false
    }
  }, [id])

  function set<K extends keyof Draft>(key: K, value: Draft[K]) {
    setDraft((current) => ({ ...current, [key]: value }))
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    // Step 2: Build the body. Weight is optional and must be a whole number.
    const weight = draft.weight.trim() === '' ? null : Number(draft.weight)
    if (weight !== null && (!Number.isInteger(weight) || weight < 0)) {
      setError('Weight must be a whole number of grams.')
      return
    }
    const input: SkuInput = {
      sku_code: draft.code.trim(),
      name: draft.name.trim(),
      barcode: draft.barcode.trim() || null,
      weight_g: weight,
      is_bundle: draft.bundle,
    }
    if (draft.productId === NEW_PRODUCT) input.product_name = draft.productName.trim()
    else input.product_id = draft.productId
    setBusy(true)
    setError('')
    setNotice('')
    try {
      // Step 3: Create navigates to the edit page; update stays and shows the saved values.
      if (sku) {
        const saved = await catalogApi.updateSku(sku.id, input)
        setSku(saved)
        setDraft(draftOf(saved))
        setNotice('Saved.')
      } else {
        const created = await catalogApi.createSku(input)
        window.location.hash = `#/catalog/skus/${created.id}`
      }
    } catch (err) {
      setError(messageFor(err, 'Could not save the SKU'))
    } finally {
      setBusy(false)
    }
  }

  async function remove() {
    if (!sku || !window.confirm(`Delete ${sku.sku_code}?`)) return
    setBusy(true)
    setError('')
    try {
      await catalogApi.deleteSku(sku.id)
      window.location.hash = '#/catalog/skus'
    } catch (err) {
      setError(messageFor(err, 'Could not delete the SKU'))
    } finally {
      setBusy(false)
    }
  }

  const readOnly = !canWrite
  const heading = id ? (sku ? sku.sku_code : 'SKU') : 'New SKU'
  return (
    <PageContent>
      <p className="text-[13px]">
        <a href="#/catalog/skus" className="font-medium text-brand-700 hover:underline">
          Back to SKUs
        </a>
      </p>
      <PageHeader className="mt-2" title={heading} />
      {readOnly ? <p className="mt-2 text-[13px] text-stone-600">You can view this SKU. Only an OWNER or ADMIN can change it.</p> : null}
      <Card className="mt-4">
        <form className="grid gap-4 p-5 sm:grid-cols-2" onSubmit={submit}>
          <div>
            <Label htmlFor="sku-product">Product</Label>
            <Select
              id="sku-product"
              value={draft.productId}
              disabled={readOnly}
              onChange={(event) => set('productId', event.target.value)}
            >
              {products.map((product) => (
                <option key={product.id} value={product.id}>
                  {product.name}
                  {product.status === 'INACTIVE' ? ' (archived)' : ''}
                </option>
              ))}
              {!sku ? <option value={NEW_PRODUCT}>New product…</option> : null}
            </Select>
          </div>
          {draft.productId === NEW_PRODUCT ? (
            <div>
              <Label htmlFor="sku-new-product">New product name</Label>
              <Input
                id="sku-new-product"
                value={draft.productName}
                disabled={readOnly}
                onChange={(event) => set('productName', event.target.value)}
              />
            </div>
          ) : null}
          <div>
            <Label htmlFor="sku-code">SKU code</Label>
            <Input id="sku-code" value={draft.code} disabled={readOnly} onChange={(event) => set('code', event.target.value)} />
          </div>
          <div>
            <Label htmlFor="sku-name">Name</Label>
            <Input id="sku-name" value={draft.name} disabled={readOnly} onChange={(event) => set('name', event.target.value)} />
          </div>
          <div>
            <Label htmlFor="sku-barcode">Barcode</Label>
            <Input
              id="sku-barcode"
              value={draft.barcode}
              disabled={readOnly}
              onChange={(event) => set('barcode', event.target.value)}
            />
          </div>
          <div>
            <Label htmlFor="sku-weight">Weight (g)</Label>
            <Input
              id="sku-weight"
              inputMode="numeric"
              value={draft.weight}
              disabled={readOnly}
              onChange={(event) => set('weight', event.target.value)}
            />
          </div>
          <div className="sm:col-span-2">
            <Label className="flex cursor-pointer items-center gap-2 font-normal">
              <input
                type="checkbox"
                className="h-4 w-4 rounded border-stone-300 text-brand-600 focus:ring-brand-200"
                checked={draft.bundle}
                disabled={readOnly}
                onChange={(event) => set('bundle', event.target.checked)}
              />
              Bundle (made of other SKUs)
            </Label>
          </div>
          {sku && !sku.is_bundle && sku.on_hand !== null ? (
            <p className="sm:col-span-2 text-[13px] text-stone-600">
              On hand {sku.on_hand} · reserved {sku.reserved ?? 0}
            </p>
          ) : null}
          {/* Change: T08A stock history link. */}
          {sku && !sku.is_bundle ? (
            <p className="sm:col-span-2 text-[13px]">
              <a href={`#/catalog/skus/${sku.id}/history`} className="font-medium text-brand-700 hover:underline">
                Stock history
              </a>
            </p>
          ) : null}
          {error ? (
            <p role="alert" className="sm:col-span-2 text-[13px] text-red-700">
              {error}
            </p>
          ) : null}
          {notice ? <p className="sm:col-span-2 text-[13px] text-stone-600">{notice}</p> : null}
          {canWrite ? (
            <div className="flex flex-wrap gap-2 sm:col-span-2">
              <Button type="submit" variant="primary" disabled={busy}>
                {sku ? 'Save' : 'Create SKU'}
              </Button>
              {sku ? (
                <Button type="button" variant="danger" disabled={busy} onClick={() => void remove()}>
                  Delete
                </Button>
              ) : null}
            </div>
          ) : null}
        </form>
      </Card>
      {sku && sku.is_bundle ? (
        <div className="mt-6">
          <BundleEditor key={sku.id} sku={sku} canWrite={canWrite} onSaved={setSku} />
        </div>
      ) : null}
    </PageContent>
  )
}
