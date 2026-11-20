import { renderToString } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from '../App'
import { CatalogError, deleteTopic, listProblems, saveProblem, saveProgress } from './api'
import { progressPatch } from './ProblemDetails'

afterEach(() => vi.unstubAllGlobals())

describe('catalog routes', () => {
  it('renders a submitted filter from the URL and offers problem creation', () => {
    const html = renderToString(
      <MemoryRouter initialEntries={['/problems?q=Two+Sum&status=SOLVED']}><App /></MemoryRouter>,
    )
    expect(html).toContain('value="Two Sum"')
    expect(html).toContain('value="SOLVED" selected=""')
    expect(html).toContain('href="/problems/new"')
    expect(html).toContain('Loading problems')
  })

  it.each([
    ['/problems/new', 'Add problem'],
    ['/problems/5/edit', 'Edit problem'],
    ['/problems/5', 'Problem details'],
  ])('renders the %s route', (path, title) => {
    expect(renderToString(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>)).toContain(`<h1>${title}</h1>`)
  })
})

describe('catalog requests', () => {
  it('retains multiple topic filters and pagination in the query', async () => {
    const page = { items: [], page: 1, size: 20, totalItems: 21, totalPages: 2 }
    const fetch = vi.fn().mockResolvedValue(Response.json(page))
    vi.stubGlobal('fetch', fetch)
    await expect(listProblems('topic=1&topic=2&page=1')).resolves.toEqual(page)
    expect(fetch).toHaveBeenCalledWith('/api/problems?topic=1&topic=2&page=1', expect.anything())
  })

  it('uses the internal problem ID for edits and sends all selected topic IDs', async () => {
    const input = { leetcodeId: 11, title: 'Container With Most Water', url: 'https://leetcode.com/problems/container-with-most-water/',
      difficulty: 'MEDIUM', topicIds: [3, 5] } as const
    const payload = { ...input, topicIds: [...input.topicIds] }
    const fetch = vi.fn().mockResolvedValue(Response.json({ id: 2, ...payload }))
    vi.stubGlobal('fetch', fetch)
    await saveProblem(payload, 2)
    expect(fetch).toHaveBeenCalledWith('/api/problems/2', expect.objectContaining({
      method: 'PUT', body: JSON.stringify(payload),
    }))
  })

  it('keeps field errors from the backend', async () => {
    const message = 'Remove this topic from its problems before deleting it.'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ message, fields: { id: message } }, { status: 409 })))
    await expect(deleteTopic(2)).rejects.toEqual(new CatalogError(message, { id: message }))
  })

  it('uses PATCH for notes and direct solves', async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json({ status: 'SOLVED' }))
    vi.stubGlobal('fetch', fetch)
    await saveProgress(5, progressPatch('SOLVED', '', '', 'Used a hash map'))
    expect(fetch).toHaveBeenCalledWith('/api/problems/5/progress', expect.objectContaining({
      method: 'PATCH', body: JSON.stringify({ status: 'SOLVED', comments: 'Used a hash map', attemptedDate: null }),
    }))
  })
})

it('submits applicable dates and drops stale values when the status changes', () => {
  expect(progressPatch('NOT_STARTED', '2026-11-18', '2026-11-19', 'Keep notes')).toEqual({
    status: 'NOT_STARTED', comments: 'Keep notes',
  })
  expect(progressPatch('ATTEMPTED', '2026-11-18', '2026-11-19', '')).toEqual({
    status: 'ATTEMPTED', attemptedDate: '2026-11-18', comments: '',
  })
})
