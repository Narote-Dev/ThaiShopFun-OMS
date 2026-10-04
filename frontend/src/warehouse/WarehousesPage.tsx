import { useEffect, useState, type FormEvent } from 'react'
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
import { catalogApi, messageFor, type Warehouse } from '../catalog/api'

type Edit = { code: string; name: string }

export default function WarehousesPage({ canWrite }: { canWrite: boolean }) {
  const [warehouses, setWarehouses] = useState<Warehouse[]>([])
  const [edits, setEdits] = useState<Record<string, Edit>>({})
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [version, setVersion] = useState(0)

  // Step 1: The first list of a shop creates the MAIN default warehouse on the server.
  useEffect(() => {
    let active = true
    catalogApi
      .listWarehouses()
      .then((body) => {
        if (!active) return
        setWarehouses(body.items)
        setEdits(Object.fromEntries(body.items.map((w) => [w.id, { code: w.code, name: w.name }])))
      })
      .catch((err: unknown) => {
        if (active) setError(messageFor(err, 'Could not load warehouses'))
      })
    return () => {
      active = false
    }
  }, [version])

  async function run(action: () => Promise<unknown>, fallback: string) {
    setBusy(true)
    setError('')
    try {
      await action()
      setVersion((v) => v + 1)
      return true
    } catch (err) {
      setError(messageFor(err, fallback))
      return false
    } finally {
      setBusy(false)
    }
  }

  async function create(event: FormEvent) {
    event.preventDefault()
    if (await run(() => catalogApi.createWarehouse(code.trim(), name.trim()), 'Could not create the warehouse')) {
      setCode('')
      setName('')
    }
  }

  return (
    <PageContent wide>
      <PageHeader title="Warehouses" />
      {error ? <p role="alert" className="mt-4 text-[13px] text-red-700">{error}</p> : null}
      <div className="mt-4">
        <DataTable aria-label="Warehouses">
          <DataTableHead>
            <tr>
              <DataTableTh>Code</DataTableTh>
              <DataTableTh>Name</DataTableTh>
              <DataTableTh>Default</DataTableTh>
              {canWrite ? <DataTableTh>Actions</DataTableTh> : null}
            </tr>
          </DataTableHead>
          <tbody>
            {warehouses.map((warehouse) => {
              const edit = edits[warehouse.id] ?? { code: warehouse.code, name: warehouse.name }
              const changed = edit.code.trim() !== warehouse.code || edit.name.trim() !== warehouse.name
              return (
                <DataTableRow key={warehouse.id}>
                  <DataTableCell>
                    {canWrite ? (
                      <Input
                        aria-label={`Code of ${warehouse.code}`}
                        value={edit.code}
                        className="mt-0"
                        onChange={(event) => setEdits({ ...edits, [warehouse.id]: { ...edit, code: event.target.value } })}
                      />
                    ) : (
                      warehouse.code
                    )}
                  </DataTableCell>
                  <DataTableCell>
                    {canWrite ? (
                      <Input
                        aria-label={`Name of ${warehouse.code}`}
                        value={edit.name}
                        className="mt-0"
                        onChange={(event) => setEdits({ ...edits, [warehouse.id]: { ...edit, name: event.target.value } })}
                      />
                    ) : (
                      warehouse.name
                    )}
                  </DataTableCell>
                  <DataTableCell>{warehouse.is_default ? 'Default' : ''}</DataTableCell>
                  {canWrite ? (
                    <DataTableCell>
                      <div className="flex flex-wrap gap-2">
                        <Button
                          type="button"
                          size="sm"
                          disabled={busy || !changed}
                          onClick={() =>
                            void run(
                              () =>
                                catalogApi.updateWarehouse(warehouse.id, edit.code.trim(), edit.name.trim(), warehouse.address),
                              'Could not save the warehouse',
                            )
                          }
                        >
                          Save
                        </Button>
                        <Button
                          type="button"
                          size="sm"
                          disabled={busy || warehouse.is_default}
                          onClick={() =>
                            void run(() => catalogApi.setDefaultWarehouse(warehouse.id), 'Could not change the default')
                          }
                        >
                          Set default
                        </Button>
                        <Button
                          type="button"
                          size="sm"
                          variant="ghost"
                          disabled={busy || warehouse.is_default}
                          onClick={() => void run(() => catalogApi.deleteWarehouse(warehouse.id), 'Could not delete')}
                        >
                          Delete
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
      {canWrite ? (
        <Card className="mt-4">
          <form className="flex flex-wrap items-end gap-4 p-4" onSubmit={create}>
            <div>
              <Label htmlFor="wh-code">Code</Label>
              <Input id="wh-code" value={code} onChange={(event) => setCode(event.target.value)} />
            </div>
            <div>
              <Label htmlFor="wh-name">Name</Label>
              <Input id="wh-name" value={name} onChange={(event) => setName(event.target.value)} />
            </div>
            <Button type="submit" variant="primary" disabled={busy || !code.trim() || !name.trim()}>
              Add warehouse
            </Button>
          </form>
        </Card>
      ) : null}
    </PageContent>
  )
}
