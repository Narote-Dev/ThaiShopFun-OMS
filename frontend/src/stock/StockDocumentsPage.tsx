import { useEffect, useState, type FormEvent } from 'react'
import type { Page } from '../catalog/api'
import type { StockAccess } from './access'
import {
  DOCUMENT_TYPES,
  stockApi,
  stockMessage,
  TYPE_LABELS,
  type DocumentFilters,
  type DocumentType,
  type StockDocument,
} from './api'

export const DOCUMENT_PAGE_SIZE = 25

const emptyFilters: DocumentFilters = { type: '', status: '', from: '', to: '' }

/** Stock documents, newest first, with filters and a form to start a new draft. */
export default function StockDocumentsPage({ access }: { access: StockAccess }) {
  const [filters, setFilters] = useState<DocumentFilters>(emptyFilters)
  const [applied, setApplied] = useState<DocumentFilters>(emptyFilters)
  const [offset, setOffset] = useState(0)
  const [page, setPage] = useState<Page<StockDocument> | null>(null)
  const [error, setError] = useState('')
  const [type, setType] = useState<DocumentType>('RECEIVE')
  const [reference, setReference] = useState('')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)

  // Step 1: Load one page for the applied filters.
  useEffect(() => {
    let active = true
    stockApi
      .listDocuments(applied, DOCUMENT_PAGE_SIZE, offset)
      .then((next) => {
        if (!active) return
        setPage(next)
        setError('')
      })
      .catch((err: unknown) => {
        if (active) setError(stockMessage(err, 'Could not load stock documents'))
      })
    return () => {
      active = false
    }
  }, [applied, offset])

  function applyFilters(event: FormEvent) {
    event.preventDefault()
    setOffset(0)
    setApplied(filters)
  }

  async function create(event: FormEvent) {
    event.preventDefault()
    // Step 2: Create the draft and open its editor.
    setBusy(true)
    setError('')
    try {
      const created = await stockApi.createDocument(type, reference.trim() || null, note.trim() || null)
      window.location.hash = `#/stock/documents/${created.id}`
    } catch (err) {
      setError(stockMessage(err, 'Could not create the document'))
    } finally {
      setBusy(false)
    }
  }

  const total = page?.total ?? 0
  const last = Math.min(offset + DOCUMENT_PAGE_SIZE, total)
  return (
    <main className="wide">
      <h1>Stock documents</h1>
      {access.canEdit ? (
        <form className="toolbar" onSubmit={create} aria-label="New stock document">
          <label>
            New document type
            <select value={type} onChange={(event) => setType(event.target.value as DocumentType)}>
              {DOCUMENT_TYPES.map((value) => (
                <option key={value} value={value}>
                  {TYPE_LABELS[value]}
                </option>
              ))}
            </select>
          </label>
          <label>
            Reference no.
            <input value={reference} onChange={(event) => setReference(event.target.value)} />
          </label>
          <label>
            Note
            <input value={note} onChange={(event) => setNote(event.target.value)} />
          </label>
          <button type="submit" disabled={busy}>
            Create document
          </button>
        </form>
      ) : null}
      <form className="toolbar" onSubmit={applyFilters} aria-label="Filter stock documents">
        <label>
          Type
          <select value={filters.type} onChange={(event) => setFilters({ ...filters, type: event.target.value })}>
            <option value="">All types</option>
            {DOCUMENT_TYPES.map((value) => (
              <option key={value} value={value}>
                {TYPE_LABELS[value]}
              </option>
            ))}
          </select>
        </label>
        <label>
          Status
          <select value={filters.status} onChange={(event) => setFilters({ ...filters, status: event.target.value })}>
            <option value="">All statuses</option>
            <option value="DRAFT">Draft</option>
            <option value="POSTED">Posted</option>
            <option value="VOID">Void</option>
          </select>
        </label>
        <label>
          From
          <input type="date" value={filters.from} onChange={(event) => setFilters({ ...filters, from: event.target.value })} />
        </label>
        <label>
          To
          <input type="date" value={filters.to} onChange={(event) => setFilters({ ...filters, to: event.target.value })} />
        </label>
        <button type="submit">Filter</button>
      </form>
      {error ? <p role="alert">{error}</p> : null}
      {page && page.items.length === 0 ? <p>No stock documents.</p> : null}
      {page && page.items.length > 0 ? (
        <table>
          <thead>
            <tr>
              <th>Created</th>
              <th>Type</th>
              <th>Reference</th>
              <th>Status</th>
              <th>Lines</th>
              <th>Posted</th>
            </tr>
          </thead>
          <tbody>
            {page.items.map((doc) => (
              <tr key={doc.id}>
                <td>
                  <a href={`#/stock/documents/${doc.id}`}>{new Date(doc.created_at).toLocaleString()}</a>
                </td>
                <td>{TYPE_LABELS[doc.type]}</td>
                <td>{doc.reference_no ?? ''}</td>
                <td>{doc.status}</td>
                <td>{doc.line_count}</td>
                <td>{doc.posted_at ? new Date(doc.posted_at).toLocaleString() : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {page && total > 0 ? (
        <p className="pager">
          <span>
            {offset + 1}–{last} of {total}
          </span>
          <button type="button" disabled={offset === 0} onClick={() => setOffset(Math.max(offset - DOCUMENT_PAGE_SIZE, 0))}>
            Previous
          </button>
          <button type="button" disabled={last >= total} onClick={() => setOffset(offset + DOCUMENT_PAGE_SIZE)}>
            Next
          </button>
        </p>
      ) : null}
    </main>
  )
}
