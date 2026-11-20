import { renderToString } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { getHealth } from './api'
import { App } from './App'

afterEach(() => vi.unstubAllGlobals())

describe('application shell', () => {
  it.each([
    ['/', 'Dashboard'],
    ['/problems', 'Problems'],
    ['/topics', 'Topics'],
    ['/goals', 'Goals'],
    ['/planner', 'Daily Planner'],
    ['/revisions', 'Revisions'],
    ['/analytics', 'Analytics'],
  ])('renders %s with the expected navigation target', (path, name) => {
    const html = renderToString(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>)
    expect(html).toContain('aria-label="Main navigation"')
    expect(html).toContain(`<h1>${name}</h1>`)
    expect(html.match(/aria-current="page"/g)).toHaveLength(1)
    if (path === '/') {
      expect(html).toContain('Checking backend')
    }
  })
})

describe('health client', () => {
  it.each([[200, 'UP'], [503, 'DOWN']] as const)('reads a valid HTTP %s response', async (code, status) => {
    const request = vi.fn().mockResolvedValue(Response.json({ status }, { status: code }))
    vi.stubGlobal('fetch', request)

    await expect(getHealth()).resolves.toEqual({ status })
    expect(request).toHaveBeenCalledWith('/api/health', expect.objectContaining({
      headers: { Accept: 'application/json' },
    }))
  })

  it('rejects an unexpected response body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ status: 'unexpected' })))
    await expect(getHealth()).rejects.toThrow('Unexpected health response')
  })

  it('propagates request failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Connection refused')))
    await expect(getHealth()).rejects.toThrow('Connection refused')
  })
})
