import { renderToString } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import { App } from '../App'
import { getCalendar, listGoalProblems, moveTask, saveGoal, skipTask, type Task } from './api'
import { shiftDate } from './dates'
import { PlannerTask } from './PlannerTask'
import { GoalProblems, GoalProblemRow } from './GoalProblems'

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

it('requests goal problems with independent display filters, sort and page', async () => {
  const fetch = vi.fn().mockResolvedValue(Response.json({ items: [], totalMatching: 47, totalItems: 3, counted: 3 }))
  vi.stubGlobal('fetch', fetch)
  const page = await listGoalProblems(5, 'COUNTED', 'STATUS', 1)
  expect(fetch).toHaveBeenCalledWith('/api/goals/5/problems?filter=COUNTED&sort=STATUS&page=1', expect.anything())
  expect(page.totalMatching).toBe(47)
  expect(page.totalItems).toBe(3)
})

it('renders goal problems collapsed without fetching and distinguishes counted and outside solves', () => {
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  const goal = {
    id: 5, name: 'Graphs', type: 'CUSTOM' as const, startDate: '2026-11-27', endDate: '2026-11-29',
    topic: null, difficulty: null, progress: { solvedCount: 1, targetCount: 50, remainingCount: 49, complete: false },
  }
  const collapsed = renderToString(<GoalProblems goal={goal} />)
  expect(collapsed).toContain('<summary>Problems</summary>')
  expect(collapsed).not.toContain('Show problems')
  expect(fetch).not.toHaveBeenCalled()

  const problem = { id: 3, leetcodeId: 127, title: 'Word Ladder', difficulty: 'HARD' as const,
    status: 'SOLVED' as const, topics: [{ id: 1, name: 'Graphs' }], attemptedDate: null, solvedDate: '2026-11-27' }
  const html = renderToString(<MemoryRouter><ul>
    <GoalProblemRow problem={{ ...problem, countsTowardGoal: true, solvedOutsideRange: false }} />
    <GoalProblemRow problem={{ ...problem, id: 4, countsTowardGoal: false, solvedOutsideRange: true }} />
  </ul></MemoryRouter>)
  expect(html).toContain('/problems/3')
  expect(html).toContain('difficulty-hard')
  expect(html).toContain('progress-solved')
  expect(html).toContain('Counts toward goal')
  expect(html).toContain('Outside goal range')
})
