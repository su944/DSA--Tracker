import { useEffect, useRef, useState } from 'react'
import { asError, FormError, LoadMessage } from '../catalog/components'
import { useResource } from '../catalog/useResource'
import {
  applyImport, cancelRestore, createBackup, getBackups, previewImport, queueRestore,
  type Backup, type Format, type ImportResult, type Mode, type Preview, type RowPreview,
} from './api'

export function SettingsPage() {
  return (
    <>
      <h1>Settings</h1>
      <div className="settings-grid">
        <CatalogExchange />
        <SqliteBackups />
      </div>
    </>
  )
}

function CatalogExchange() {
  const [file, setFile] = useState<File | null>(null)
  const [format, setFormat] = useState<Format>('CSV')
  const [mode, setMode] = useState<Mode>('SKIP_EXISTING')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [result, setResult] = useState<ImportResult | null>(null)
  const [busy, setBusy] = useState<'preview' | 'apply' | null>(null)
  const [error, setError] = useState<Error | null>(null)

  function clearResult() {
    setPreview(null)
    setResult(null)
    setError(null)
  }

  async function run(operation: 'preview' | 'apply') {
    if (!file || busy) return
    setError(null)
    setBusy(operation)
    try {
      if (operation === 'preview') {
        setResult(null)
        setPreview(null)
        setPreview(await previewImport(file, format, mode))
      } else {
        setResult(await applyImport(file, format, mode))
        setPreview(null)
      }
    } catch (failure) {
      setError(asError(failure))
    } finally {
      setBusy(null)
    }
  }

  return (
    <section className="catalog-panel settings-panel" aria-labelledby="exchange-heading">
      <h2 id="exchange-heading">Catalog import and export</h2>
      <p className="body-text">Download problem metadata, topics, and progress. Imports match by LeetCode ID and preserve existing progress and comments.</p>
      <div className="catalog-actions settings-actions">
        <a className="catalog-button" href="/api/exchange/export?format=CSV">Export CSV</a>
        <a className="catalog-button" href="/api/exchange/export?format=JSON">Export JSON</a>
      </div>
      <form onSubmit={(event) => { event.preventDefault(); void run('preview') }}>
        <fieldset disabled={busy !== null}>
          <div className="catalog-form-grid">
            <label className="catalog-wide">Catalog file<input type="file" accept=".csv,.json,text/csv,application/json" required
              onChange={(event) => {
                const selected = event.target.files?.[0] ?? null
                setFile(selected)
                if (selected) setFormat(selected.name.toLowerCase().endsWith('.json') ? 'JSON' : 'CSV')
                clearResult()
              }} /></label>
            <label>Format<select value={format} onChange={(event) => { setFormat(event.target.value as Format); clearResult() }}>
              <option value="CSV">CSV</option><option value="JSON">JSON</option>
            </select></label>
            <label>Existing problems<select value={mode} onChange={(event) => { setMode(event.target.value as Mode); clearResult() }}>
              <option value="SKIP_EXISTING">Skip existing</option><option value="UPDATE_EXISTING">Update catalog fields</option>
            </select></label>
          </div>
          <button type="submit" className="catalog-button primary" disabled={!file}>{busy === 'preview' ? 'Previewing…' : 'Preview import'}</button>
        </fieldset>
      </form>
      <FormError error={error} />
      {preview && (
        <div className="settings-results">
          <h3>Import preview</h3>
          <p role="status">{preview.creates} to create · {preview.updates} to update · {preview.skipped} to skip · {preview.invalid} invalid</p>
          <RowList rows={preview.rows} />
          <button type="button" className="catalog-button primary" disabled={busy !== null || preview.creates + preview.updates === 0}
            onClick={() => run('apply')}>{busy === 'apply' ? 'Importing…' : 'Import valid rows'}</button>
        </div>
      )}
      {result && (
        <div className="settings-results">
          <h3>Import results</h3>
          <p className={result.failed ? 'catalog-message' : 'catalog-success'} role="status">
            {result.created} created · {result.updated} updated · {result.skipped} skipped · {result.failed} failed
          </p>
          <RowList rows={result.errors} />
        </div>
      )}
    </section>
  )
}

const rowAction = { CREATE: 'Create', UPDATE: 'Update', SKIP: 'Skip', INVALID: 'Invalid' } as const

function RowList({ rows }: { rows: RowPreview[] }) {
  const [page, setPage] = useState(0)
  const size = 20
  if (!rows.length) return null
  return (
    <div className="settings-results">
      <ul className="catalog-list">
        {rows.slice(page * size, (page + 1) * size).map((row) => (
          <li key={row.row}>
            <div className="settings-row">
              <span>Row {row.row}{row.leetcodeId ? ` · #${row.leetcodeId}` : ''}{row.title ? ` · ${row.title}` : ''}</span>
              <span className={`catalog-badge ${row.action === 'INVALID' ? 'difficulty-hard' : ''}`}>{rowAction[row.action]}</span>
            </div>
            {Object.entries(row.errors).map(([field, error]) => <p className="body-text" key={field}>{field}: {error}</p>)}
          </li>
        ))}
      </ul>
      {rows.length > size && <div className="catalog-actions settings-actions">
        <button type="button" className="catalog-button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous rows</button>
        <button type="button" className="catalog-button" disabled={(page + 1) * size >= rows.length} onClick={() => setPage(page + 1)}>Next rows</button>
      </div>}
    </div>
  )
}

function SqliteBackups() {
  const { result, retry } = useResource(getBackups)
  const [selected, setSelected] = useState<Backup | null>(null)
  const [busy, setBusy] = useState(false)
  const [success, setSuccess] = useState('')
  const [error, setError] = useState<Error | null>(null)

  async function change(action: () => Promise<unknown>, message: string) {
    if (busy) return
    setBusy(true)
    setError(null)
    setSuccess('')
    try {
      await action()
      setSuccess(message)
      setSelected(null)
      retry()
    } catch (failure) {
      setError(asError(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="catalog-panel settings-panel" aria-labelledby="backup-heading">
      <h2 id="backup-heading">SQLite backups</h2>
      <p className="body-text">Backups include all tracker data. Restore takes effect after you stop and restart the backend.</p>
      <div className="catalog-actions settings-actions">
        <button type="button" className="catalog-button primary" disabled={busy}
          onClick={() => change(createBackup, 'Backup created.')}>Create backup</button>
      </div>
      {success && <p className="catalog-success" role="status">{success}</p>}
      <FormError error={error} />
      {result.state !== 'ready' ? <LoadMessage label="backups" retry={retry} error={result.state === 'error' ? result.error : undefined} /> : (
        <>
          {result.data.pendingRestoreId && <div className="catalog-message">
            <p role="status">A restore is pending. Stop and restart the backend to apply it.</p>
            <button type="button" className="catalog-button" disabled={busy}
              onClick={() => change(cancelRestore, 'Pending restore cancelled.')}>Cancel pending restore</button>
          </div>}
          {result.data.backups.length === 0 ? <p className="body-text">No backups yet.</p> : (
            <ul className="catalog-list">
              {result.data.backups.map((backup) => (
                <li key={backup.id} className="settings-row">
                  <div>
                    <strong><time dateTime={backup.createdAt}>{new Date(backup.createdAt).toLocaleString()}</time></strong>
                    <p className="body-text">{Math.ceil(backup.bytes / 1024).toLocaleString()} KB · {backup.id}</p>
                  </div>
                  <button type="button" className="catalog-button danger"
                    disabled={busy || Boolean(result.data.pendingRestoreId)}
                    onClick={() => { setSelected(backup); setError(null); setSuccess('') }}>Restore</button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      {selected && <RestoreDialog backup={selected} onDismiss={() => setSelected(null)} onQueued={() => {
        setSelected(null)
        setSuccess('Restore queued. Restart the backend.')
        retry()
      }} />}
    </section>
  )
}

function RestoreDialog({ backup, onDismiss, onQueued }: {
  backup: Backup; onDismiss: () => void; onQueued: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState<Error | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    const element = dialog.current
    element?.showModal()
    return () => element?.close()
  }, [])

  function dismiss() {
    if (busy) return
    dialog.current?.close()
    onDismiss()
  }

  async function confirm() {
    if (busy || confirmation !== 'RESTORE') return
    setBusy(true)
    setError(null)
    try {
      await queueRestore(backup.id, confirmation)
      dialog.current?.close()
      onQueued()
    } catch (failure) {
      setError(asError(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <dialog ref={dialog} className="catalog-panel tracker-dialog"
      aria-labelledby="restore-heading" aria-describedby="restore-description" onCancel={(event) => {
        event.preventDefault()
        dismiss()
      }}>
      <form className="settings-results" onSubmit={(event) => { event.preventDefault(); void confirm() }}>
        <h2 id="restore-heading">Restore database backup</h2>
        <p id="restore-description">Backup {backup.id} will replace the current database at the next startup. A recovery copy will be saved first.</p>
        <FormError error={error} />
        <label>Type RESTORE to confirm<input value={confirmation} onChange={(event) => setConfirmation(event.target.value)}
          required spellCheck={false} autoComplete="off" readOnly={busy} /></label>
        <div className="catalog-actions settings-actions">
          <button type="submit" className="catalog-button danger" disabled={busy || confirmation !== 'RESTORE'}>{busy ? 'Queueing…' : 'Queue restore'}</button>
          <button type="button" className="catalog-button" disabled={busy} onClick={dismiss}>Keep current database</button>
        </div>
      </form>
    </dialog>
  )
}
