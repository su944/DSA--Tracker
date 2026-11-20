import { renderToString } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import { App } from '../App'
import { ActivityChart, DifficultyChart, TopicChart } from './Charts'
import { getAnalytics } from './api'

afterEach(() => vi.unstubAllGlobals())

it('shows separate loading states for totals, today, and analytics on the Dashboard', () => {
  const html = renderToString(<MemoryRouter initialEntries={['/?range=7']}><App /></MemoryRouter>)
  expect(html).toContain('Loading problem totals')
  expect(html).toContain('Loading today’s plan and goals')
  expect(html).toContain('Loading analytics')
  expect(html).toContain('value="7" selected=""')
  expect(html).toContain('Checking backend')
})

it('applies the same range to all analytics endpoints', async () => {
  const fetch = vi.fn().mockImplementation(() => Promise.resolve(Response.json([])))
  vi.stubGlobal('fetch', fetch)
  await getAnalytics('90', new AbortController().signal)
  expect(fetch.mock.calls.map(([url]) => url)).toEqual([
    '/api/analytics/topics?range=90',
    '/api/analytics/difficulty?range=90',
    '/api/analytics/activity?range=90',
    '/api/analytics/recent?range=90',
  ])
})

it('renders the remaining count separately from solves outside the selected period', () => {
  const html = renderToString(
    <MemoryRouter><TopicChart filtered topics={[{ topicId: 8, name: 'Arrays', total: 5, solved: 2, remaining: 1 }]} /></MemoryRouter>,
  ).replaceAll('<!-- -->', '')
  expect(html).toContain('2 solved · 1 remaining · 2 outside range')
  expect(html).toContain('href="/problems?topic=8"')
  expect(html.match(/width:40%/g)).toHaveLength(2)
})

it('compares all difficulty levels, including unsolved levels', () => {
  const html = renderToString(<DifficultyChart values={[{ difficulty: 'MEDIUM', solved: 3 }, { difficulty: 'HARD', solved: 1 }]} />)
    .replaceAll('<!-- -->', '')
  expect(html).toContain('Easy')
  expect(html).toContain('0 solved · 0%')
  expect(html).toContain('3 solved · 75%')
  expect(html).toContain('1 solved · 25%')
})

it('labels individual activity bars and handles an empty period', () => {
  const activity = { startDate: '2026-11-19', endDate: '2026-11-20',
    days: [{ date: '2026-11-19', solved: 0 }, { date: '2026-11-20', solved: 2 }] }
  const html = renderToString(<ActivityChart activity={activity} />)
  expect(html).toContain('aria-label="Nov 19, 2026: 0 solved"')
  expect(html).toContain('aria-label="Nov 20, 2026: 2 solved"')
  expect(renderToString(<ActivityChart activity={{ ...activity, days: [] }} />)).toContain('No dated solves in this range')
})
