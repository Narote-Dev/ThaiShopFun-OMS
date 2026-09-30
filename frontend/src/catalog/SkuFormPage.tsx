import { useEffect, useState, type FormEvent } from 'react'
import BundleEditor from './BundleEditor'
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
  return (
    <main>
      <p>
        <a href="#/catalog/skus">Back to SKUs</a>
      </p>
      <h1>{id ? (sku ? sku.sku_code : 'SKU') : 'New SKU'}</h1>
      {readOnly ? <p>You can view this SKU. Only an OWNER or ADMIN can change it.</p> : null}
      <form onSubmit={submit}>
        <label>
          Product
          <select
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
          </select>
        </label>
        {draft.productId === NEW_PRODUCT ? (
          <label>
            New product name
            <input
              value={draft.productName}
              disabled={readOnly}
              onChange={(event) => set('productName', event.target.value)}
            />
          </label>
        ) : null}
        <label>
          SKU code
          <input value={draft.code} disabled={readOnly} onChange={(event) => set('code', event.target.value)} />
        </label>
        <label>
          Name
          <input value={draft.name} disabled={readOnly} onChange={(event) => set('name', event.target.value)} />
        </label>
        <label>
          Barcode
          <input
            value={draft.barcode}
            disabled={readOnly}
            onChange={(event) => set('barcode', event.target.value)}
          />
        </label>
        <label>
          Weight (g)
          <input
            inputMode="numeric"
            value={draft.weight}
            disabled={readOnly}
            onChange={(event) => set('weight', event.target.value)}
          />
        </label>
        <label className="check">
          <input
            type="checkbox"
            checked={draft.bundle}
            disabled={readOnly}
            onChange={(event) => set('bundle', event.target.checked)}
          />
          Bundle (made of other SKUs)
        </label>
        {sku && !sku.is_bundle && sku.on_hand !== null ? (
          <p>
            On hand {sku.on_hand} · reserved {sku.reserved ?? 0}
          </p>
        ) : null}
        {sku && !sku.is_bundle ? (
          <p>
            <a href={`#/catalog/skus/${sku.id}/history`}>Stock history</a>
          </p>
        ) : null}
        {error ? <p role="alert">{error}</p> : null}
        {notice ? <p>{notice}</p> : null}
        {canWrite ? (
          <p>
            <button type="submit" disabled={busy}>
              {sku ? 'Save' : 'Create SKU'}
            </button>
            {sku ? (
              <button type="button" disabled={busy} onClick={() => void remove()}>
                Delete
              </button>
            ) : null}
          </p>
        ) : null}
      </form>
      {sku && sku.is_bundle ? (
        <BundleEditor key={sku.id} sku={sku} canWrite={canWrite} onSaved={setSku} />
      ) : null}
    </main>
  )
}
