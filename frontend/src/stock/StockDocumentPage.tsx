import { useEffect, useState, type FormEvent } from 'react'
import { catalogApi, type Sku, type Warehouse } from '../catalog/api'
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
import { Select } from '../ui/Select'
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
      <PageContent>
        <p className="text-[13px]">
          <a href="#/stock/documents" className="font-medium text-brand-700 hover:underline">
            Back to stock documents
          </a>
        </p>
        {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : <p role="status" className="mt-4 text-[13px] text-stone-600">Loading…</p>}
      </PageContent>
    )
  }

  const draft = doc.status === 'DRAFT'
  const editable = draft && access.canEdit
  const count = doc.type === 'COUNT'
  const reasons = doc.type === 'ADJUSTMENT' || doc.type === 'WRITE_OFF'
  const lines = doc.lines ?? []
  return (
    <PageContent wide>
      <p className="text-[13px]">
        <a href="#/stock/documents" className="font-medium text-brand-700 hover:underline">
          Back to stock documents
        </a>
      </p>
      <PageHeader
        className="mt-2"
        title={
          <>
            {TYPE_LABELS[doc.type]} · {doc.status}
          </>
        }
      />
      {count && doc.count_started_at ? (
        <p className="mt-2 text-[13px] text-stone-600">Count started {new Date(doc.count_started_at).toLocaleString()}</p>
      ) : null}
      {doc.posted_at ? <p className="text-[13px] text-stone-600">Posted {new Date(doc.posted_at).toLocaleString()}</p> : null}
      {!access.canEdit ? <p className="text-[13px] text-stone-600">This shop is read-only.</p> : null}
      <Card className="mt-4">
        <form
          className="flex flex-wrap items-end gap-4 p-4"
          aria-label="Document header"
          onSubmit={(event) => {
            event.preventDefault()
            void run(() => stockApi.updateDocument(id, reference.trim() || null, note.trim() || null), 'Could not save', 'Saved.')
          }}
        >
          <div className="min-w-[160px] flex-1">
            <Label htmlFor="doc-ref">Reference no.</Label>
            <Input id="doc-ref" value={reference} disabled={!editable} onChange={(event) => setReference(event.target.value)} />
          </div>
          <div className="min-w-[160px] flex-1">
            <Label htmlFor="doc-note">Note</Label>
            <Input id="doc-note" value={note} disabled={!editable} onChange={(event) => setNote(event.target.value)} />
          </div>
          {editable ? (
            <Button type="submit" variant="primary" disabled={busy}>
              Save header
            </Button>
          ) : null}
        </form>
      </Card>
      {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : null}
      {notice ? <p role="status" className="mt-2 text-[13px] text-stone-600">{notice}</p> : null}
      <div className="mt-4">
        <DataTable aria-label="Lines">
          <DataTableHead>
            <tr>
              <DataTableTh>SKU</DataTableTh>
              <DataTableTh>Warehouse</DataTableTh>
              {count ? <DataTableTh>System at start</DataTableTh> : null}
              {count ? <DataTableTh>Counted</DataTableTh> : <DataTableTh>Qty</DataTableTh>}
              {reasons ? <DataTableTh>Reason</DataTableTh> : null}
              <DataTableTh>On hand / reserved</DataTableTh>
              <DataTableTh>Problem</DataTableTh>
              {editable ? <DataTableTh>Actions</DataTableTh> : null}
            </tr>
          </DataTableHead>
          <tbody>
            {lines.map((line) => {
              const edit = edits[line.id] ?? editOf(line)
              const problem = problemFor(line)
              return (
                <DataTableRow key={line.id}>
                  <DataTableCell>
                    {line.sku_code} <small className="text-stone-500">{line.sku_name}</small>
                  </DataTableCell>
                  <DataTableCell>
                    {editable ? (
                      <Select
                        aria-label={`Warehouse for ${line.sku_code}`}
                        value={edit.warehouse}
                        className="mt-0"
                        onChange={(event) => setEdit(line.id, 'warehouse', event.target.value)}
                      >
                        {warehouses.map((w) => (
                          <option key={w.id} value={w.id}>
                            {w.code}
                          </option>
                        ))}
                      </Select>
                    ) : (
                      line.warehouse_code
                    )}
                  </DataTableCell>
                  {count ? <DataTableCell>{line.system_qty_at_start ?? '—'}</DataTableCell> : null}
                  <DataTableCell>
                    {editable ? (
                      <Input
                        aria-label={`${count ? 'Counted' : 'Qty'} for ${line.sku_code}`}
                        inputMode="numeric"
                        value={count ? edit.counted : edit.qty}
                        className="mt-0"
                        onChange={(event) => setEdit(line.id, count ? 'counted' : 'qty', event.target.value)}
                      />
                    ) : count ? (
                      `${line.counted_qty ?? '—'}${doc.status !== 'DRAFT' ? ` (${line.qty >= 0 ? '+' : ''}${line.qty})` : ''}`
                    ) : (
                      line.qty
                    )}
                  </DataTableCell>
                  {reasons ? (
                    <DataTableCell>
                      {editable ? (
                        <Select
                          aria-label={`Reason for ${line.sku_code}`}
                          value={edit.reason}
                          className="mt-0"
                          onChange={(event) => setEdit(line.id, 'reason', event.target.value)}
                        >
                          <option value="">No reason</option>
                          {ADJUSTMENT_REASONS.map((r) => (
                            <option key={r} value={r}>
                              {r}
                            </option>
                          ))}
                        </Select>
                      ) : (
                        (line.reason_code ?? '')
                      )}
                    </DataTableCell>
                  ) : null}
                  <DataTableCell>
                    {line.on_hand ?? 0} / {line.reserved ?? 0}
                  </DataTableCell>
                  <DataTableCell>{problem ? <span role="alert">{`${problem.error}: ${problem.message}`}</span> : null}</DataTableCell>
                  {editable ? (
                    <DataTableCell>
                      <div className="flex flex-wrap gap-2">
                        <Button type="button" size="sm" disabled={busy} onClick={() => saveLine(line)}>
                          Save
                        </Button>
                        <Button
                          type="button"
                          size="sm"
                          variant="ghost"
                          disabled={busy}
                          onClick={() => void run(() => stockApi.deleteLine(id, line.id), 'Could not remove the line')}
                        >
                          Remove
                        </Button>
                      </div>
                    </DataTableCell>
                  ) : null}
                </DataTableRow>
              )
            })}
          </tbody>
        </DataTable>
      </div>
      {lines.length === 0 ? <p className="mt-4 text-[13px] text-stone-600">No lines yet.</p> : null}
      {editable ? (
        <Card className="mt-4">
          <form className="grid gap-4 p-4 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-5" onSubmit={addLine} aria-label="Add line">
            <div>
              <Label htmlFor="add-line-sku">SKU code</Label>
              <Input id="add-line-sku" list="stock-sku-options" value={skuCode} onChange={(event) => setSkuCode(event.target.value)} />
            </div>
            <datalist id="stock-sku-options">
              {suggestions.map((sku) => (
                <option key={sku.id} value={sku.sku_code}>
                  {sku.name}
                </option>
              ))}
            </datalist>
            <div>
              <Label htmlFor="add-line-wh">Warehouse</Label>
              <Select id="add-line-wh" value={newWarehouse} onChange={(event) => setNewWarehouse(event.target.value)}>
                {warehouses.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.code}
                  </option>
                ))}
              </Select>
            </div>
            {count ? (
              <div>
                <Label htmlFor="add-line-counted">Counted qty</Label>
                <Input
                  id="add-line-counted"
                  inputMode="numeric"
                  value={newCounted}
                  onChange={(event) => setNewCounted(event.target.value)}
                />
              </div>
            ) : (
              <div>
                <Label htmlFor="add-line-qty">Qty</Label>
                <Input id="add-line-qty" inputMode="numeric" value={newQty} onChange={(event) => setNewQty(event.target.value)} />
              </div>
            )}
            {reasons ? (
              <div>
                <Label htmlFor="add-line-reason">Reason</Label>
                <Select id="add-line-reason" value={newReason} onChange={(event) => setNewReason(event.target.value)}>
                  <option value="">No reason</option>
                  {ADJUSTMENT_REASONS.map((r) => (
                    <option key={r} value={r}>
                      {r}
                    </option>
                  ))}
                </Select>
              </div>
            ) : null}
            <div className="flex items-end">
              <Button type="submit" variant="primary" disabled={busy || skuCode.trim() === ''}>
                Add line
              </Button>
            </div>
          </form>
        </Card>
      ) : null}
      <div className="mt-4 flex flex-wrap items-center gap-2">
        {editable && count && !doc.count_started_at ? (
          <Button
            type="button"
            variant="secondary"
            disabled={busy}
            onClick={() => void run(() => stockApi.startCount(id), 'Could not start the count', 'Count started.')}
          >
            Start count
          </Button>
        ) : null}
        {draft && access.canPost(doc.type) ? (
          <Button type="button" variant="primary" disabled={busy || lines.length === 0} onClick={post}>
            Post
          </Button>
        ) : null}
        {draft && !access.canPost(doc.type) && access.canEdit ? (
          <span className="text-[13px] text-stone-600">Only an OWNER or ADMIN can post this document.</span>
        ) : null}
        {doc.status === 'POSTED' && access.canVoid ? (
          <Button type="button" variant="danger" disabled={busy} onClick={voidDocument}>
            Void
          </Button>
        ) : null}
        {editable ? (
          <Button type="button" variant="ghost" disabled={busy} onClick={() => void remove()}>
            Delete draft
          </Button>
        ) : null}
      </div>
    </PageContent>
  )
}
