import { useEffect, useState, type FormEvent } from 'react'
import type { Page } from '../catalog/api'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import {
  DataTable,
  DataTableCell,
  DataTableHead,
  DataTableRow,
  DataTableTh,
  TablePager,
} from '../ui/DataTable'
import { Input } from '../ui/Input'
import { Label } from '../ui/Label'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'
import { Select } from '../ui/Select'
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
    <PageContent wide>
      <PageHeader title="Stock documents" />
      {access.canEdit ? (
        <Card className="mt-4">
          <form
            className="grid gap-4 p-4 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-5"
            onSubmit={create}
            aria-label="New stock document"
          >
            <div>
              <Label htmlFor="new-doc-type">New document type</Label>
              <Select id="new-doc-type" value={type} onChange={(event) => setType(event.target.value as DocumentType)}>
                {DOCUMENT_TYPES.map((value) => (
                  <option key={value} value={value}>
                    {TYPE_LABELS[value]}
                  </option>
                ))}
              </Select>
            </div>
            <div>
              <Label htmlFor="new-doc-ref">Reference no.</Label>
              <Input id="new-doc-ref" value={reference} onChange={(event) => setReference(event.target.value)} />
            </div>
            <div>
              <Label htmlFor="new-doc-note">Note</Label>
              <Input id="new-doc-note" value={note} onChange={(event) => setNote(event.target.value)} />
            </div>
            <div className="flex items-end">
              <Button type="submit" variant="primary" disabled={busy}>
                Create document
              </Button>
            </div>
          </form>
        </Card>
      ) : null}
      <Card className="mt-4">
        <form
          className="grid gap-4 p-4 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-5"
          onSubmit={applyFilters}
          aria-label="Filter stock documents"
        >
          <div>
            <Label htmlFor="filter-doc-type">Type</Label>
            <Select
              id="filter-doc-type"
              value={filters.type}
              onChange={(event) => setFilters({ ...filters, type: event.target.value })}
            >
              <option value="">All types</option>
              {DOCUMENT_TYPES.map((value) => (
                <option key={value} value={value}>
                  {TYPE_LABELS[value]}
                </option>
              ))}
            </Select>
          </div>
          <div>
            <Label htmlFor="filter-doc-status">Status</Label>
            <Select
              id="filter-doc-status"
              value={filters.status}
              onChange={(event) => setFilters({ ...filters, status: event.target.value })}
            >
              <option value="">All statuses</option>
              <option value="DRAFT">Draft</option>
              <option value="POSTED">Posted</option>
              <option value="VOID">Void</option>
            </Select>
          </div>
          <div>
            <Label htmlFor="filter-doc-from">From</Label>
            <Input
              id="filter-doc-from"
              type="date"
              value={filters.from}
              onChange={(event) => setFilters({ ...filters, from: event.target.value })}
            />
          </div>
          <div>
            <Label htmlFor="filter-doc-to">To</Label>
            <Input
              id="filter-doc-to"
              type="date"
              value={filters.to}
              onChange={(event) => setFilters({ ...filters, to: event.target.value })}
            />
          </div>
          <div className="flex items-end">
            <Button type="submit" variant="primary">
              Filter
            </Button>
          </div>
        </form>
      </Card>
      {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : null}
      {page && page.items.length === 0 ? <p className="mt-4 text-[13px] text-stone-600">No stock documents.</p> : null}
      {page && page.items.length > 0 ? (
        <div className="mt-4">
          <DataTable aria-label="Stock documents">
            <DataTableHead>
              <tr>
                <DataTableTh>Created</DataTableTh>
                <DataTableTh>Type</DataTableTh>
                <DataTableTh>Reference</DataTableTh>
                <DataTableTh>Status</DataTableTh>
                <DataTableTh>Lines</DataTableTh>
                <DataTableTh>Posted</DataTableTh>
              </tr>
            </DataTableHead>
            <tbody>
              {page.items.map((doc) => (
                <DataTableRow key={doc.id}>
                  <DataTableCell>
                    <a href={`#/stock/documents/${doc.id}`} className="font-medium text-brand-700 hover:underline">
                      {new Date(doc.created_at).toLocaleString()}
                    </a>
                  </DataTableCell>
                  <DataTableCell>{TYPE_LABELS[doc.type]}</DataTableCell>
                  <DataTableCell>{doc.reference_no ?? ''}</DataTableCell>
                  <DataTableCell>{doc.status}</DataTableCell>
                  <DataTableCell>{doc.line_count}</DataTableCell>
                  <DataTableCell>{doc.posted_at ? new Date(doc.posted_at).toLocaleString() : ''}</DataTableCell>
                </DataTableRow>
              ))}
            </tbody>
          </DataTable>
        </div>
      ) : null}
      {page && total > 0 ? (
        <TablePager
          summary={
            <span>
              {offset + 1}–{last} of {total}
            </span>
          }
        >
          <Button
            type="button"
            variant="secondary"
            disabled={offset === 0}
            onClick={() => setOffset(Math.max(offset - DOCUMENT_PAGE_SIZE, 0))}
          >
            Previous
          </Button>
          <Button type="button" variant="secondary" disabled={last >= total} onClick={() => setOffset(offset + DOCUMENT_PAGE_SIZE)}>
            Next
          </Button>
        </TablePager>
      ) : null}
    </PageContent>
  )
}
