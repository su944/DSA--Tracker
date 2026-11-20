import { renderToString } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import { App } from '../App'
import { request } from '../catalog/api'
import { finishRevision, getRevisions } from '../revision/api'
import { previewImport, queueRestore } from './api'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

it('makes import, export and SQLite backups available in Settings', () => {
  const html = renderToString(<MemoryRouter initialEntries={['/settings']}><App /></MemoryRouter>)
  expect(html).toContain('<h1>Settings</h1>')
  expect(html).toContain('Preview import')
  expect(html).toContain('Export CSV')
  expect(html).toContain('Export JSON')
  expect(html).toContain('Create backup')
})

it('uploads multipart data for preview while preserving the default skip mode', async () => {
  const fetch = vi.fn().mockResolvedValue(Response.json({ rows: [] }))
  vi.stubGlobal('fetch', fetch)
  const file = new File(['leetcodeId,title'], 'catalog.csv', { type: 'text/csv' })
  await previewImport(file, 'CSV', 'SKIP_EXISTING')
  expect(fetch.mock.calls[0][0]).toBe('/api/exchange/preview?format=CSV&mode=SKIP_EXISTING')
  expect(fetch.mock.calls[0][1].headers.has('Content-Type')).toBe(false)
  expect(fetch.mock.calls[0][1].body.get('file').name).toBe('catalog.csv')
})

it('posts explicit confirmation when queuing a backup restore', async () => {
  const fetch = vi.fn().mockResolvedValue(Response.json({ restartRequired: true }, { status: 202 }))
  vi.stubGlobal('fetch', fetch)
  await expect(queueRestore('12345678-1234-1234-1234-123456789abc', 'RESTORE'))
    .resolves.toMatchObject({ restartRequired: true })
  expect(fetch).toHaveBeenCalledWith('/api/backups/12345678-1234-1234-1234-123456789abc/restore',
    expect.objectContaining({ method: 'POST', body: '{"confirmation":"RESTORE"}' }))
})

it('loads revision filters and submits completion without touching the catalog API', async () => {
  const fetch = vi.fn().mockImplementation(() => Promise.resolve(Response.json({})))
  vi.stubGlobal('fetch', fetch)
  await getRevisions('OVERDUE')
  await finishRevision(14, 'complete')
  expect(fetch).toHaveBeenNthCalledWith(1, '/api/revisions?filter=OVERDUE', expect.anything())
  expect(fetch).toHaveBeenNthCalledWith(2, '/api/revisions/14/complete', expect.objectContaining({ method: 'POST' }))
})

it('warns that a timed-out mutation may still be running', async () => {
  vi.spyOn(AbortSignal, 'timeout').mockReturnValue(AbortSignal.abort())
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new DOMException('Timeout', 'TimeoutError')))
  await expect(request('exchange/import', { method: 'POST', timeoutMs: 300_000 }))
    .rejects.toThrow('This operation may still be running.')
})
