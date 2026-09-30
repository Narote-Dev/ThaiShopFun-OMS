import { useState, type FormEvent } from 'react'
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
    <main className="wide">
      <h1>Import catalog</h1>
      <p>
        UTF-8 CSV with a header row. Columns: <code>product_name, sku_code, sku_name, barcode, weight_g,
        is_bundle, components</code>. <code>components</code> is <code>CODE:qty|CODE:qty</code> and may name SKUs
        anywhere in the file. An existing <code>sku_code</code> is updated, so the same file can be imported
        again. For an existing SKU, an optional column left out of the header keeps the stored value; an empty
        cell clears it. Products are matched by <code>product_name</code>. If any row is wrong, nothing is imported.
      </p>
      <details>
        <summary>Example</summary>
        <pre>{SAMPLE}</pre>
      </details>
      {canWrite ? (
        <form className="toolbar" onSubmit={upload}>
          <label>
            CSV file
            <input
              type="file"
              accept=".csv,text/csv"
              onChange={(event) => setFile(event.target.files?.[0] ?? null)}
            />
          </label>
          <button type="submit" disabled={busy || !file}>
            {busy ? 'Importing…' : 'Import'}
          </button>
        </form>
      ) : (
        <p>Only an OWNER or ADMIN can import, and not while the shop is read-only.</p>
      )}
      {error ? <p role="alert">{error}</p> : null}
      {rowErrors.length > 0 ? (
        <table aria-label="Row errors">
          <thead>
            <tr>
              <th>Row</th>
              <th>Column</th>
              <th>Error</th>
            </tr>
          </thead>
          <tbody>
            {rowErrors.map((row, index) => (
              <tr key={`${row.row}-${row.column ?? ''}-${index}`}>
                <td>{row.row}</td>
                <td>{row.column ?? '—'}</td>
                <td>{row.error}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {result ? (
        <p>
          Imported {result.rows} rows: {result.skus_created} created, {result.skus_updated} updated,{' '}
          {result.skus_unchanged} unchanged, {result.bundles_replaced} bundles updated, {result.products_created}{' '}
          new products.
        </p>
      ) : null}
    </main>
  )
}
