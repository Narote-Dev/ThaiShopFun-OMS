import { useEffect, useState, type FormEvent } from 'react'
import { catalogApi, type Sku, type Warehouse } from '../catalog/api'
import type { StockAccess } from './access'
import {
  ADJUSTMENT_REASONS,
  problemsOf,
  stockApi,
  stockMessage,
  TYPE_LABELS,
  type DocumentLine,
  type LineInput,
  type LineProblem,
  type StockDocument,
} from './api'

type RowEdit = { warehouse: string; qty: string; counted: string; reason: string }

function editOf(line: DocumentLine): RowEdit {
  return {
    warehouse: line.warehouse_id,
    qty: String(line.qty),
    counted: line.counted_qty === null ? '' : String(line.counted_qty),
    reason: line.reason_code ?? '',
  }
}

function whole(value: string): number | null {
  if (value.trim() === '') return null
  const n = Number(value)
  return Number.isInteger(n) ? n : Number.NaN
}

/** One stock document: header, line grid, count start, post and void. */
export default function StockDocumentPage({ id, access }: { id: string; access: StockAccess }) {
  const [doc, setDoc] = useState<StockDocument | null>(null)
  const [warehouses, setWarehouses] = useState<Warehouse[]>([])
  const [reference, setReference] = useState('')
  const [note, setNote] = useState('')
  const [edits, setEdits] = useState<Record<string, RowEdit>>({})
  const [skuCode, setSkuCode] = useState('')
  const [suggestions, setSuggestions] = useState<Sku[]>([])
  const [newWarehouse, setNewWarehouse] = useState('')
  const [newQty, setNewQty] = useState('')
  const [newCounted, setNewCounted] = useState('')
  const [newReason, setNewReason] = useState('')
  const [problems, setProblems] = useState<LineProblem[]>([])
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [version, setVersion] = useState(0)

  // Step 1: Load the document and the warehouses (the first list creates MAIN).
  useEffect(() => {
    let active = true
    Promise.all([stockApi.getDocument(id), catalogApi.listWarehouses()])
      .then(([loaded, list]) => {
        if (!active) return
        setDoc(loaded)
        setReference(loaded.reference_no ?? '')
        setNote(loaded.note ?? '')
        setEdits(Object.fromEntries((loaded.lines ?? []).map((line) => [line.id, editOf(line)])))
        setWarehouses(list.items)
        setNewWarehouse((current) => current || (list.items.find((w) => w.is_default)?.id ?? list.items[0]?.id ?? ''))
      })
      .catch((err: unknown) => {
        if (active) setError(stockMessage(err, 'Could not load the document'))
      })
    return () => {
      active = false
    }
  }, [id, version])

  // Step 2: SKU picker. Suggest plain SKUs whose code starts with what was typed.
  useEffect(() => {
    const q = skuCode.trim()
    if (q.length < 2) return
    let active = true
    catalogApi
      .listSkus(q, 10, 0)
      .then((page) => {
        if (active) setSuggestions(page.items.filter((sku) => !sku.is_bundle))
      })
      .catch(() => undefined)
    return () => {
      active = false
    }
  }, [skuCode])

  async function run(action: () => Promise<unknown>, fallback: string, done?: string) {
    setBusy(true)
    setError('')
    setNotice('')
    setProblems([])
    try {
      await action()
      if (done) setNotice(done)
      setVersion((v) => v + 1)
      return true
    } catch (err) {
      setError(stockMessage(err, fallback))
      setProblems(problemsOf(err))
      return false
    } finally {
      setBusy(false)
    }
  }

  function lineInput(edit: { warehouse: string; qty: string; counted: string; reason: string }): LineInput | null {
    const qty = whole(edit.qty)
    const counted = whole(edit.counted)
    if (Number.isNaN(qty) || Number.isNaN(counted)) {
      setError('Quantities must be whole numbers.')
      return null
    }
    const input: LineInput = { warehouse_id: edit.warehouse || null }
    if (doc?.type === 'COUNT') input.counted_qty = counted
    else input.qty = qty
    if (doc?.type === 'ADJUSTMENT' || doc?.type === 'WRITE_OFF') input.reason_code = edit.reason || null
    return input
  }

  async function addLine(event: FormEvent) {
    event.preventDefault()
    const input = lineInput({ warehouse: newWarehouse, qty: newQty, counted: newCounted, reason: newReason })
    if (!input) return
    const ok = await run(() => stockApi.addLine(id, { ...input, sku_code: skuCode.trim() }), 'Could not add the line')
    if (ok) {
      setSkuCode('')
      setNewQty('')
      setNewCounted('')
      setNewReason('')
    }
  }

  function saveLine(line: DocumentLine) {
    const input = lineInput(edits[line.id] ?? editOf(line))
    if (!input) return
    void run(() => stockApi.updateLine(id, line.id, { ...input, sku_id: line.sku_id }), 'Could not save the line', 'Line saved.')
  }

  function setEdit(lineId: string, key: keyof RowEdit, value: string) {
    setEdits((current) => ({ ...current, [lineId]: { ...current[lineId], [key]: value } }))
  }

  function post() {
    if (!doc || !window.confirm(`Post this ${TYPE_LABELS[doc.type].toLowerCase()}? Stock changes right away.`)) return
    void run(async () => {
      const result = await stockApi.post(id)
      setNotice(`Posted. ${result.movements.length} ledger entries written.`)
    }, 'Could not post the document')
  }

  function voidDocument() {
    if (!doc || !window.confirm('Void this document? Every stock change it made is reversed.')) return
    void run(async () => {
      const result = await stockApi.voidDocument(id)
      setNotice(`Voided. ${result.movements.length} reversing entries written.`)
    }, 'Could not void the document')
  }

  async function remove() {
    if (!window.confirm('Delete this draft?')) return
    if (await run(() => stockApi.deleteDocument(id), 'Could not delete the draft')) window.location.hash = '#/stock/documents'
  }

  function problemFor(line: DocumentLine): LineProblem | undefined {
    return (
      problems.find((p) => p.line_id === line.id) ??
      problems.find((p) => !p.line_id && p.sku_id === line.sku_id && p.warehouse_id === line.warehouse_id)
    )
  }

  if (!doc) {
    return (
      <main>
        <p>
          <a href="#/stock/documents">Back to stock documents</a>
        </p>
        {error ? <p role="alert">{error}</p> : <p role="status">Loading…</p>}
      </main>
    )
  }

  const draft = doc.status === 'DRAFT'
  const editable = draft && access.canEdit
  const count = doc.type === 'COUNT'
  const reasons = doc.type === 'ADJUSTMENT' || doc.type === 'WRITE_OFF'
  const lines = doc.lines ?? []
  return (
    <main className="wide">
      <p>
        <a href="#/stock/documents">Back to stock documents</a>
      </p>
      <h1>
        {TYPE_LABELS[doc.type]} · {doc.status}
      </h1>
      {count && doc.count_started_at ? <p>Count started {new Date(doc.count_started_at).toLocaleString()}</p> : null}
      {doc.posted_at ? <p>Posted {new Date(doc.posted_at).toLocaleString()}</p> : null}
      {!access.canEdit ? <p>This shop is read-only.</p> : null}
      <form
        className="toolbar"
        aria-label="Document header"
        onSubmit={(event) => {
          event.preventDefault()
          void run(() => stockApi.updateDocument(id, reference.trim() || null, note.trim() || null), 'Could not save', 'Saved.')
        }}
      >
        <label>
          Reference no.
          <input value={reference} disabled={!editable} onChange={(event) => setReference(event.target.value)} />
        </label>
        <label>
          Note
          <input value={note} disabled={!editable} onChange={(event) => setNote(event.target.value)} />
        </label>
        {editable ? (
          <button type="submit" disabled={busy}>
            Save header
          </button>
        ) : null}
      </form>
      {error ? <p role="alert">{error}</p> : null}
      {notice ? <p role="status">{notice}</p> : null}
      <table aria-label="Lines">
        <thead>
          <tr>
            <th>SKU</th>
            <th>Warehouse</th>
            {count ? <th>System at start</th> : null}
            {count ? <th>Counted</th> : <th>Qty</th>}
            {reasons ? <th>Reason</th> : null}
            <th>On hand / reserved</th>
            <th>Problem</th>
            {editable ? <th>Actions</th> : null}
          </tr>
        </thead>
        <tbody>
          {lines.map((line) => {
            const edit = edits[line.id] ?? editOf(line)
            const problem = problemFor(line)
            return (
              <tr key={line.id}>
                <td>
                  {line.sku_code} <small>{line.sku_name}</small>
                </td>
                <td>
                  {editable ? (
                    <select
                      aria-label={`Warehouse for ${line.sku_code}`}
                      value={edit.warehouse}
                      onChange={(event) => setEdit(line.id, 'warehouse', event.target.value)}
                    >
                      {warehouses.map((w) => (
                        <option key={w.id} value={w.id}>
                          {w.code}
                        </option>
                      ))}
                    </select>
                  ) : (
                    line.warehouse_code
                  )}
                </td>
                {count ? <td>{line.system_qty_at_start ?? '—'}</td> : null}
                <td>
                  {editable ? (
                    <input
                      aria-label={`${count ? 'Counted' : 'Qty'} for ${line.sku_code}`}
                      inputMode="numeric"
                      value={count ? edit.counted : edit.qty}
                      onChange={(event) => setEdit(line.id, count ? 'counted' : 'qty', event.target.value)}
                    />
                  ) : count ? (
                    `${line.counted_qty ?? '—'}${doc.status !== 'DRAFT' ? ` (${line.qty >= 0 ? '+' : ''}${line.qty})` : ''}`
                  ) : (
                    line.qty
                  )}
                </td>
                {reasons ? (
                  <td>
                    {editable ? (
                      <select
                        aria-label={`Reason for ${line.sku_code}`}
                        value={edit.reason}
                        onChange={(event) => setEdit(line.id, 'reason', event.target.value)}
                      >
                        <option value="">No reason</option>
                        {ADJUSTMENT_REASONS.map((r) => (
                          <option key={r} value={r}>
                            {r}
                          </option>
                        ))}
                      </select>
                    ) : (
                      (line.reason_code ?? '')
                    )}
                  </td>
                ) : null}
                <td>
                  {line.on_hand ?? 0} / {line.reserved ?? 0}
                </td>
                <td>{problem ? <span role="alert">{`${problem.error}: ${problem.message}`}</span> : null}</td>
                {editable ? (
                  <td>
                    <button type="button" disabled={busy} onClick={() => saveLine(line)}>
                      Save
                    </button>
                    <button type="button" disabled={busy} onClick={() => void run(() => stockApi.deleteLine(id, line.id), 'Could not remove the line')}>
                      Remove
                    </button>
                  </td>
                ) : null}
              </tr>
            )
          })}
        </tbody>
      </table>
      {lines.length === 0 ? <p>No lines yet.</p> : null}
      {editable ? (
        <form className="toolbar" onSubmit={addLine} aria-label="Add line">
          <label>
            SKU code
            <input list="stock-sku-options" value={skuCode} onChange={(event) => setSkuCode(event.target.value)} />
          </label>
          <datalist id="stock-sku-options">
            {suggestions.map((sku) => (
              <option key={sku.id} value={sku.sku_code}>
                {sku.name}
              </option>
            ))}
          </datalist>
          <label>
            Warehouse
            <select value={newWarehouse} onChange={(event) => setNewWarehouse(event.target.value)}>
              {warehouses.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.code}
                </option>
              ))}
            </select>
          </label>
          {count ? (
            <label>
              Counted qty
              <input inputMode="numeric" value={newCounted} onChange={(event) => setNewCounted(event.target.value)} />
            </label>
          ) : (
            <label>
              Qty
              <input inputMode="numeric" value={newQty} onChange={(event) => setNewQty(event.target.value)} />
            </label>
          )}
          {reasons ? (
            <label>
              Reason
              <select value={newReason} onChange={(event) => setNewReason(event.target.value)}>
                <option value="">No reason</option>
                {ADJUSTMENT_REASONS.map((r) => (
                  <option key={r} value={r}>
                    {r}
                  </option>
                ))}
              </select>
            </label>
          ) : null}
          <button type="submit" disabled={busy || skuCode.trim() === ''}>
            Add line
          </button>
        </form>
      ) : null}
      <p className="toolbar">
        {editable && count && !doc.count_started_at ? (
          <button type="button" disabled={busy} onClick={() => void run(() => stockApi.startCount(id), 'Could not start the count', 'Count started.')}>
            Start count
          </button>
        ) : null}
        {draft && access.canPost(doc.type) ? (
          <button type="button" disabled={busy || lines.length === 0} onClick={post}>
            Post
          </button>
        ) : null}
        {draft && !access.canPost(doc.type) && access.canEdit ? (
          <span>Only an OWNER or ADMIN can post this document.</span>
        ) : null}
        {doc.status === 'POSTED' && access.canVoid ? (
          <button type="button" disabled={busy} onClick={voidDocument}>
            Void
          </button>
        ) : null}
        {editable ? (
          <button type="button" disabled={busy} onClick={() => void remove()}>
            Delete draft
          </button>
        ) : null}
      </p>
    </main>
  )
}
