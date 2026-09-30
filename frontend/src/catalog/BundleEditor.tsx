import { useState } from 'react'
import { catalogApi, messageFor, type Sku } from './api'

type Line = { key: number; code: string; qty: string }

let nextKey = 1

function linesOf(sku: Sku): Line[] {
  return (sku.components ?? []).map((c) => ({ key: nextKey++, code: c.sku_code, qty: String(c.qty) }))
}

/** Replaces the whole component list in one call (PUT /skus/{id}/components). */
export default function BundleEditor({
  sku,
  canWrite,
  onSaved,
}: {
  sku: Sku
  canWrite: boolean
  onSaved: (sku: Sku) => void
}) {
  const [lines, setLines] = useState<Line[]>(() => linesOf(sku))
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)

  function change(key: number, patch: Partial<Line>) {
    setLines((current) => current.map((line) => (line.key === key ? { ...line, ...patch } : line)))
  }

  async function save() {
    // Step 1: Validate locally for a quick message. The server re-checks every rule.
    const components = lines
      .filter((line) => line.code.trim() !== '')
      .map((line) => ({ component_sku_code: line.code.trim(), qty: Number(line.qty) }))
    if (components.some((c) => !Number.isInteger(c.qty) || c.qty < 1)) {
      setError('Quantities must be whole numbers of at least 1.')
      return
    }
    setBusy(true)
    setError('')
    setNotice('')
    try {
      const saved = await catalogApi.replaceComponents(sku.id, components)
      setLines(linesOf(saved))
      setNotice('Components saved.')
      onSaved(saved)
    } catch (err) {
      setError(messageFor(err, 'Could not save components'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-label="Bundle components">
      <h2>Bundle components</h2>
      <p>Components must be plain SKUs. Stock of a bundle is derived from its components.</p>
      {lines.length === 0 ? <p>No components yet.</p> : null}
      <ul>
        {lines.map((line, index) => (
          <li key={line.key}>
            <label>
              Component {index + 1} code
              <input
                value={line.code}
                disabled={!canWrite}
                onChange={(event) => change(line.key, { code: event.target.value })}
              />
            </label>
            <label>
              Qty
              <input
                type="number"
                min={1}
                value={line.qty}
                disabled={!canWrite}
                onChange={(event) => change(line.key, { qty: event.target.value })}
              />
            </label>
            {canWrite ? (
              <button type="button" onClick={() => setLines(lines.filter((l) => l.key !== line.key))}>
                Remove
              </button>
            ) : null}
          </li>
        ))}
      </ul>
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p>{notice}</p> : null}
      {canWrite ? (
        <p>
          <button type="button" onClick={() => setLines([...lines, { key: nextKey++, code: '', qty: '1' }])}>
            Add component
          </button>
          <button type="button" disabled={busy} onClick={() => void save()}>
            Save components
          </button>
        </p>
      ) : null}
    </section>
  )
}
