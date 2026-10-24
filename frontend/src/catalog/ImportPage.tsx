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
import { labelTh, importErrorTh } from '../ui/labels'
import { Input } from '../ui/Input'
import { Label } from '../ui/Label'
import { PageContent } from '../ui/PageContent'
import { PageHeader } from '../ui/PageHeader'
import { catalogApi, ImportInvalidError, messageFor, type ImportResult, type ImportRowError } from './api'

const SAMPLE =
  'product_name,sku_code,sku_name,barcode,weight_g,is_bundle,components\n' +
  'เสื้อยืด,TS-RED-M,เสื้อยืดแดงขนาดกลาง,8850000000011,180,false,\n' +
  'เสื้อยืด,TS-RED-L,เสื้อยืดแดงขนาดใหญ่,8850000000028,200,false,\n' +
  'ชุดของขวัญ,SET-RED,เสื้อยืดแดงคู่,,,true,TS-RED-M:1|TS-RED-L:1\n'

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
        setError('ข้อมูลในไฟล์ไม่ถูกต้อง ยังไม่ได้นำเข้าข้อมูล')
        setRowErrors(err.errors)
      } else {
        setError(messageFor(err, 'นำเข้าไม่สำเร็จ'))
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
            <span aria-hidden="true">นำเข้าข้อมูล</span>
            <span className="sr-only">นำเข้าข้อมูลสินค้า</span>
          </>
        }
      />
      <p className="mt-4 text-[13px] leading-relaxed text-stone-600">
        ใช้ไฟล์ซีเอสวีที่เข้ารหัสยูทีเอฟแปดและมีแถวหัวตาราง โดยใช้ชื่อคอลัมน์ตามตัวอย่างด้านล่าง
        คอลัมน์ส่วนประกอบใช้รูปแบบ รหัสสินค้า:จำนวน คั่นแต่ละรายการด้วยเครื่องหมาย | และอ้างถึงสินค้าได้ทุกตำแหน่งในไฟล์
        หากรหัสสินค้ามีอยู่แล้ว ระบบจะแก้ไขข้อมูล จึงนำเข้าไฟล์เดิมซ้ำได้
        สำหรับสินค้าที่มีอยู่แล้ว การละคอลัมน์ที่ไม่บังคับจะเก็บค่าเดิมไว้ ส่วนช่องว่างจะล้างค่า
        ระบบจับคู่สินค้าด้วยชื่อ หากแถวใดผิดพลาด ระบบจะไม่นำเข้าทั้งไฟล์
      </p>
      <details className="mt-4 text-[13px]">
        <summary className="cursor-pointer font-medium text-stone-800">ตัวอย่าง</summary>
        <pre className="mt-2 overflow-x-auto rounded-lg border border-stone-200 bg-stone-50 p-3 text-[12px]">{SAMPLE}</pre>
      </details>
      {canWrite ? (
        <Card className="mt-4">
          <form className="flex flex-wrap items-end gap-4 p-4" onSubmit={upload}>
            <div>
              <Label htmlFor="import-csv">ไฟล์ซีเอสวี</Label>
              <Input
                id="import-csv"
                type="file"
                accept=".csv,text/csv"
                className="sr-only"
                tabIndex={-1}
                onChange={(event) => setFile(event.target.files?.[0] ?? null)}
              />
              <div className="mt-1 flex items-center gap-3">
                <Button type="button" variant="secondary" onClick={() => document.getElementById('import-csv')?.click()}>
                  เลือกไฟล์
                </Button>
                <span className="text-[13px] text-stone-600">{file?.name ?? 'ยังไม่ได้เลือกไฟล์'}</span>
              </div>
            </div>
            <Button type="submit" variant="primary" disabled={busy || !file}>
              {busy ? 'กำลังนำเข้า…' : 'นำเข้าข้อมูล'}
            </Button>
          </form>
        </Card>
      ) : (
        <p className="mt-4 text-[13px] text-stone-600">เฉพาะเจ้าของร้านหรือผู้ดูแลระบบเท่านั้นที่นำเข้าได้ และร้านค้าต้องไม่อยู่ในโหมดอ่านอย่างเดียว</p>
      )}
      {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : null}
      {rowErrors.length > 0 ? (
        <div className="mt-4">
          <DataTable aria-label="ข้อผิดพลาดรายแถว">
            <DataTableHead>
              <tr>
                <DataTableTh>แถว</DataTableTh>
                <DataTableTh>คอลัมน์</DataTableTh>
                <DataTableTh>ข้อผิดพลาด</DataTableTh>
              </tr>
            </DataTableHead>
            <tbody>
              {rowErrors.map((row, index) => (
                <DataTableRow key={`${row.row}-${row.column ?? ''}-${index}`}>
                  <DataTableCell>{row.row}</DataTableCell>
                  <DataTableCell>{labelTh(row.column)}</DataTableCell>
                  <DataTableCell>{importErrorTh(row.error)}</DataTableCell>
                </DataTableRow>
              ))}
            </tbody>
          </DataTable>
        </div>
      ) : null}
      {result ? (
        <p className="mt-4 text-[13px] text-stone-700">
          นำเข้าแล้ว {result.rows} แถว: {result.skus_created} สร้างใหม่, {result.skus_updated} แก้ไข,{' '}
          {result.skus_unchanged} ไม่เปลี่ยนแปลง, {result.bundles_replaced} สินค้าชุดที่แก้ไข, {result.products_created}{' '}
          สินค้าใหม่
        </p>
      ) : null}
    </PageContent>
  )
}
