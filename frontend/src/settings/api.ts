import { request } from '../catalog/api'

export type Format = 'CSV' | 'JSON'
export type Mode = 'SKIP_EXISTING' | 'UPDATE_EXISTING'
export type RowPreview = {
  row: number; leetcodeId: number | null; title: string; action: 'CREATE' | 'UPDATE' | 'SKIP' | 'INVALID';
  errors: Record<string, string>
}
export type Preview = { total: number; creates: number; updates: number; skipped: number; invalid: number; rows: RowPreview[] }
export type ImportResult = { total: number; created: number; updated: number; skipped: number; failed: number; errors: RowPreview[] }
export type Backup = { id: string; createdAt: string; bytes: number }
export type Backups = { backups: Backup[]; pendingRestoreId: string | null }

function upload<T>(operation: string, file: File, format: Format, mode: Mode) {
  const data = new FormData()
  data.append('file', file)
  return request<T>(`exchange/${operation}?${new URLSearchParams({ format, mode })}`, { method: 'POST', body: data, timeoutMs: 300_000 })
}

export function previewImport(file: File, format: Format, mode: Mode) {
  return upload<Preview>('preview', file, format, mode)
}

export function applyImport(file: File, format: Format, mode: Mode) {
  return upload<ImportResult>('import', file, format, mode)
}

export function getBackups(signal?: AbortSignal) {
  return request<Backups>('backups', { signal })
}

export function createBackup() {
  return request<Backup>('backups', { method: 'POST', timeoutMs: 120_000 })
}

export function queueRestore(id: string, confirmation: string) {
  return request<{ backupId: string; restartRequired: boolean }>(`backups/${encodeURIComponent(id)}/restore`, {
    method: 'POST', body: JSON.stringify({ confirmation }), timeoutMs: 120_000,
  })
}

export function cancelRestore() {
  return request<void>('backups/restore', { method: 'DELETE' })
}
