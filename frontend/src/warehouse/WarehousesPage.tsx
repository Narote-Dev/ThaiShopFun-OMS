import { useEffect, useState, type FormEvent } from 'react'
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
    <main className="wide">
      <h1>Warehouses</h1>
      {error ? <p role="alert">{error}</p> : null}
      <table>
        <thead>
          <tr>
            <th>Code</th>
            <th>Name</th>
            <th>Default</th>
            {canWrite ? <th>Actions</th> : null}
          </tr>
        </thead>
        <tbody>
          {warehouses.map((warehouse) => {
            const edit = edits[warehouse.id] ?? { code: warehouse.code, name: warehouse.name }
            const changed = edit.code.trim() !== warehouse.code || edit.name.trim() !== warehouse.name
            return (
              <tr key={warehouse.id}>
                <td>
                  {canWrite ? (
                    <input
                      aria-label={`Code of ${warehouse.code}`}
                      value={edit.code}
                      onChange={(event) => setEdits({ ...edits, [warehouse.id]: { ...edit, code: event.target.value } })}
                    />
                  ) : (
                    warehouse.code
                  )}
                </td>
                <td>
                  {canWrite ? (
                    <input
                      aria-label={`Name of ${warehouse.code}`}
                      value={edit.name}
                      onChange={(event) => setEdits({ ...edits, [warehouse.id]: { ...edit, name: event.target.value } })}
                    />
                  ) : (
                    warehouse.name
                  )}
                </td>
                <td>{warehouse.is_default ? 'Default' : ''}</td>
                {canWrite ? (
                  <td>
                    <button
                      type="button"
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
                    </button>
                    <button
                      type="button"
                      disabled={busy || warehouse.is_default}
                      onClick={() =>
                        void run(() => catalogApi.setDefaultWarehouse(warehouse.id), 'Could not change the default')
                      }
                    >
                      Set default
                    </button>
                    <button
                      type="button"
                      disabled={busy || warehouse.is_default}
                      onClick={() => void run(() => catalogApi.deleteWarehouse(warehouse.id), 'Could not delete')}
                    >
                      Delete
                    </button>
                  </td>
                ) : null}
              </tr>
            )
          })}
        </tbody>
      </table>
      {canWrite ? (
        <form className="toolbar" onSubmit={create}>
          <label>
            Code
            <input value={code} onChange={(event) => setCode(event.target.value)} />
          </label>
          <label>
            Name
            <input value={name} onChange={(event) => setName(event.target.value)} />
          </label>
          <button type="submit" disabled={busy || !code.trim() || !name.trim()}>
            Add warehouse
          </button>
        </form>
      ) : null}
    </main>
  )
}
