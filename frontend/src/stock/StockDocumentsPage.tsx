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
import { labelTh } from '../ui/labels'
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
        if (active) setError(stockMessage(err, 'โหลดเอกสารสต็อกไม่สำเร็จ'))
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
      setError(stockMessage(err, 'สร้างเอกสารไม่สำเร็จ'))
    } finally {
      setBusy(false)
    }
  }

  const total = page?.total ?? 0
  const last = Math.min(offset + DOCUMENT_PAGE_SIZE, total)
  return (
    <PageContent wide>
      <PageHeader
        title={
          <>
            <span aria-hidden="true">เอกสารสต็อก</span>
            <span className="sr-only">เอกสารสต็อก</span>
          </>
        }
      />
      {access.canEdit ? (
        <Card className="mt-4">
          <form
            className="grid gap-4 p-4 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-5"
            onSubmit={create}
            aria-label="เอกสารสต็อกใหม่"
          >
            <div>
              <Label htmlFor="new-doc-type">ประเภทเอกสารใหม่</Label>
              <Select id="new-doc-type" value={type} onChange={(event) => setType(event.target.value as DocumentType)}>
                {DOCUMENT_TYPES.map((value) => (
                  <option key={value} value={value}>
                    {TYPE_LABELS[value]}
                  </option>
                ))}
              </Select>
            </div>
            <div>
              <Label htmlFor="new-doc-ref">เลขที่อ้างอิง</Label>
              <Input id="new-doc-ref" value={reference} onChange={(event) => setReference(event.target.value)} />
            </div>
            <div>
              <Label htmlFor="new-doc-note">หมายเหตุ</Label>
              <Input id="new-doc-note" value={note} onChange={(event) => setNote(event.target.value)} />
            </div>
            <div className="flex items-end">
              <Button type="submit" variant="primary" disabled={busy}>
                สร้างเอกสาร
              </Button>
            </div>
          </form>
        </Card>
      ) : null}
      <Card className="mt-4">
        <form
          className="grid gap-4 p-4 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-5"
          onSubmit={applyFilters}
          aria-label="ตัวกรองเอกสารสต็อก"
        >
          <div>
            <Label htmlFor="filter-doc-type">ประเภท</Label>
            <Select
              id="filter-doc-type"
              value={filters.type}
              onChange={(event) => setFilters({ ...filters, type: event.target.value })}
            >
              <option value="">ทุกประเภท</option>
              {DOCUMENT_TYPES.map((value) => (
                <option key={value} value={value}>
                  {TYPE_LABELS[value]}
                </option>
              ))}
            </Select>
          </div>
          <div>
            <Label htmlFor="filter-doc-status">สถานะ</Label>
            <Select
              id="filter-doc-status"
              value={filters.status}
              onChange={(event) => setFilters({ ...filters, status: event.target.value })}
            >
              <option value="">ทุกสถานะ</option>
              <option value="DRAFT">{labelTh('DRAFT')}</option>
              <option value="POSTED">{labelTh('POSTED')}</option>
              <option value="VOID">{labelTh('VOID')}</option>
            </Select>
          </div>
          <div>
            <Label htmlFor="filter-doc-from">ตั้งแต่</Label>
            <Input
              id="filter-doc-from"
              type="date"
              value={filters.from}
              onChange={(event) => setFilters({ ...filters, from: event.target.value })}
            />
          </div>
          <div>
            <Label htmlFor="filter-doc-to">ถึง</Label>
            <Input
              id="filter-doc-to"
              type="date"
              value={filters.to}
              onChange={(event) => setFilters({ ...filters, to: event.target.value })}
            />
          </div>
          <div className="flex items-end">
            <Button type="submit" variant="primary">
              กรอง
            </Button>
          </div>
        </form>
      </Card>
      {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : null}
      {page && page.items.length === 0 ? <p className="mt-4 text-[13px] text-stone-600">ยังไม่มีเอกสารสต็อก</p> : null}
      {page && page.items.length > 0 ? (
        <div className="mt-4">
          <DataTable aria-label="เอกสารสต็อก">
            <DataTableHead>
              <tr>
                <DataTableTh>สร้างเมื่อ</DataTableTh>
                <DataTableTh>ประเภท</DataTableTh>
                <DataTableTh>อ้างอิง</DataTableTh>
                <DataTableTh>สถานะ</DataTableTh>
                <DataTableTh>รายการ</DataTableTh>
                <DataTableTh>ลงบัญชีแล้ว</DataTableTh>
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
                  <DataTableCell>{labelTh(doc.status)}</DataTableCell>
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
              {offset + 1}–{last} จาก {total}
            </span>
          }
        >
          <Button
            type="button"
            variant="secondary"
            disabled={offset === 0}
            onClick={() => setOffset(Math.max(offset - DOCUMENT_PAGE_SIZE, 0))}
          >
            ก่อนหน้า
          </Button>
          <Button type="button" variant="secondary" disabled={last >= total} onClick={() => setOffset(offset + DOCUMENT_PAGE_SIZE)}>
            ถัดไป
          </Button>
        </TablePager>
      ) : null}
    </PageContent>
  )
}
