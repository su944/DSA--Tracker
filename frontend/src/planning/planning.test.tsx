import { renderToString } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import { App } from '../App'
import { getCalendar, moveTask, saveGoal, skipTask, type Task } from './api'
import { shiftDate } from './dates'
import { PlannerTask } from './PlannerTask'

afterEach(() => vi.unstubAllGlobals())

it.each([
  ['/goals', 'Add goal', 'Loading goals'],
  ['/planner?view=WEEK', 'Weekly view', 'Loading calendar'],
])('renders the %s action and loading state', (url, action, loading) => {
  const html = renderToString(<MemoryRouter initialEntries={[url]}><App /></MemoryRouter>)
  expect(html).toContain(action)
  expect(html).toContain(loading)
})

it('requests a weekly calendar with its selected date', async () => {
  const fetch = vi.fn().mockResolvedValue(Response.json({ days: [] }))
  vi.stubGlobal('fetch', fetch)
  await getCalendar('WEEK', '2026-11-20')
  expect(fetch).toHaveBeenCalledWith('/api/planner?view=WEEK&date=2026-11-20', expect.anything())
})

it('sends custom goal dates and a topic restriction when editing', async () => {
  const input = { name: 'Array practice', type: 'CUSTOM', startDate: '2026-11-16', endDate: '2026-11-20',
    targetCount: 3, topicId: 4 } as const
  const fetch = vi.fn().mockResolvedValue(Response.json({ id: 3 }))
  vi.stubGlobal('fetch', fetch)
  await saveGoal(input, 3)
  expect(fetch).toHaveBeenCalledWith('/api/goals/3', expect.objectContaining({ method: 'PUT', body: JSON.stringify(input) }))
})

it('uses separate carry-forward and restore actions', async () => {
  const fetch = vi.fn().mockImplementation(() => Promise.resolve(Response.json({ id: 2 })))
  vi.stubGlobal('fetch', fetch)
  await moveTask(2, '2026-11-21', true)
  await skipTask(2, false)
  expect(fetch).toHaveBeenNthCalledWith(1, '/api/planner/2/carry-forward',
    expect.objectContaining({ method: 'POST', body: '{"date":"2026-11-21"}' }))
  expect(fetch).toHaveBeenNthCalledWith(2, '/api/planner/2/skip',
    expect.objectContaining({ method: 'PATCH', body: '{"skipped":false}' }))
})

it('shows completion and removal for completed tasks', () => {
  const task: Task = { id: 2, date: '2026-11-20', state: 'COMPLETED',
    problem: { id: 5, leetcodeId: 1, title: 'Two Sum', status: 'SOLVED', solvedDate: '2026-11-20' } }
  const html = renderToString(<MemoryRouter><PlannerTask task={task} onChanged={() => {}} /></MemoryRouter>)
  expect(html).toContain('Completed')
  expect(html).toContain('Remove')
  expect(html).not.toContain('Carry forward')
  expect(html).not.toContain('Reschedule')
})

it('moves calendar dates across month and leap-year boundaries', () => {
  expect(shiftDate('2024-02-28', 1)).toBe('2024-02-29')
  expect(shiftDate('2026-12-30', 7)).toBe('2027-01-06')
  expect(shiftDate('9999-12-31', 1)).toBe('')
})
