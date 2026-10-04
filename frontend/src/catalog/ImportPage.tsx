import { useState, type FormEvent } from 'react'
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
import { catalogApi, ImportInvalidError, messageFor, type ImportResult, type ImportRowError } from './api'

const SAMPLE =
  'product_name,sku_code,sku_name,barcode,weight_g,is_bundle,components\n' +
  'T-Shirt,TS-RED-M,Red T-Shirt M,8850000000011,180,false,\n' +
  'T-Shirt,TS-RED-L,Red T-Shirt L,8850000000028,200,false,\n' +
  'Gift set,SET-RED,Red T-Shirt pair,,,true,TS-RED-M:1|TS-RED-L:1\n'

export default function ImportPage({ canWrite }: { canWrite: boolean }) {
  const [file, setFile] = useState<File | null>(null)
  const [result, setResult] = useState<ImportResult | null>(null)
  const [rowErrors, setRowErrors] = useState<ImportRowError[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function upload(event: FormEvent) {
    event.preventDefault()
    if (!file) return
    setBusy(true)
    setError('')
    setResult(null)
    setRowErrors([])
    try {
      setResult(await catalogApi.importCsv(file))
    } catch (err) {
      // Step 1: All-or-nothing. A rejected file lists every bad row; nothing was imported.
      if (err instanceof ImportInvalidError) {
        setError(err.message)
        setRowErrors(err.errors)
      } else {
        setError(messageFor(err, 'Import failed'))
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <PageContent wide>
      <PageHeader
        title={
          <>
            <span aria-hidden="true">นำเข้าข้อมูล (Import)</span>
            <span className="sr-only">Import catalog</span>
          </>
        }
      />
      <p className="mt-4 text-[13px] leading-relaxed text-stone-600">
        UTF-8 CSV with a header row. Columns: <code className="rounded bg-stone-100 px-1">product_name, sku_code, sku_name, barcode, weight_g,
        is_bundle, components</code>. <code className="rounded bg-stone-100 px-1">components</code> is <code className="rounded bg-stone-100 px-1">CODE:qty|CODE:qty</code> and may name SKUs
        anywhere in the file. An existing <code className="rounded bg-stone-100 px-1">sku_code</code> is updated, so the same file can be imported
        again. For an existing SKU, an optional column left out of the header keeps the stored value; an empty
        cell clears it. Products are matched by <code className="rounded bg-stone-100 px-1">product_name</code>. If any row is wrong, nothing is imported.
      </p>
      <details className="mt-4 text-[13px]">
        <summary className="cursor-pointer font-medium text-stone-800">Example</summary>
        <pre className="mt-2 overflow-x-auto rounded-lg border border-stone-200 bg-stone-50 p-3 text-[12px]">{SAMPLE}</pre>
      </details>
      {canWrite ? (
        <Card className="mt-4">
          <form className="flex flex-wrap items-end gap-4 p-4" onSubmit={upload}>
            <div>
              <Label htmlFor="import-csv">CSV file</Label>
              <Input
                id="import-csv"
                type="file"
                accept=".csv,text/csv"
                className="py-1.5 file:mr-3 file:rounded-md file:border-0 file:bg-stone-100 file:px-3 file:text-[13px] file:font-medium"
                onChange={(event) => setFile(event.target.files?.[0] ?? null)}
              />
            </div>
            <Button type="submit" variant="primary" disabled={busy || !file}>
              {busy ? 'Importing…' : 'Import'}
            </Button>
          </form>
        </Card>
      ) : (
        <p className="mt-4 text-[13px] text-stone-600">Only an OWNER or ADMIN can import, and not while the shop is read-only.</p>
      )}
      {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : null}
      {rowErrors.length > 0 ? (
        <div className="mt-4">
          <DataTable aria-label="Row errors">
            <DataTableHead>
              <tr>
                <DataTableTh>Row</DataTableTh>
                <DataTableTh>Column</DataTableTh>
                <DataTableTh>Error</DataTableTh>
              </tr>
            </DataTableHead>
            <tbody>
              {rowErrors.map((row, index) => (
                <DataTableRow key={`${row.row}-${row.column ?? ''}-${index}`}>
                  <DataTableCell>{row.row}</DataTableCell>
                  <DataTableCell>{row.column ?? '—'}</DataTableCell>
                  <DataTableCell>{row.error}</DataTableCell>
                </DataTableRow>
              ))}
            </tbody>
          </DataTable>
        </div>
      ) : null}
      {result ? (
        <p className="mt-4 text-[13px] text-stone-700">
          Imported {result.rows} rows: {result.skus_created} created, {result.skus_updated} updated,{' '}
          {result.skus_unchanged} unchanged, {result.bundles_replaced} bundles updated, {result.products_created}{' '}
          new products.
        </p>
      ) : null}
    </PageContent>
  )
}
