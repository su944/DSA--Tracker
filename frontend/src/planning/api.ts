import { request, type Difficulty, type ProgressStatus, type Topic } from '../catalog/api'

export const goalTypes = { DAILY: 'Daily', WEEKLY: 'Weekly', CUSTOM: 'Custom range' } as const
export type GoalType = keyof typeof goalTypes
export type GoalInput = {
  name: string
  type: GoalType
  startDate: string
  endDate?: string
  targetCount: number
  topicId: number | null
}
export type GoalProgress = { solvedCount: number; targetCount: number; remainingCount: number; complete: boolean }
export type Goal = {
  id: number
  name: string
  type: GoalType
  startDate: string
  endDate: string
  topic: Topic | null
  difficulty: Difficulty | null
  progress: GoalProgress
}

export const goalProblemFilters = {
  ALL: 'All', COUNTED: 'Counts toward goal', OUTSIDE: 'Solved outside goal range',
  ATTEMPTED: 'Attempted', NOT_STARTED: 'Not started',
} as const
export const goalProblemSorts = { LEETCODE_ID: 'LeetCode ID', DIFFICULTY: 'Difficulty', STATUS: 'Progress status' } as const
export type GoalProblemFilter = keyof typeof goalProblemFilters
export type GoalProblemSort = keyof typeof goalProblemSorts
export type GoalProblem = {
  id: number; leetcodeId: number; title: string; difficulty: Difficulty; topics: Pick<Topic, 'id' | 'name'>[]
  status: ProgressStatus; attemptedDate: string | null; solvedDate: string | null
  countsTowardGoal: boolean; solvedOutsideRange: boolean
}
export type GoalProblemPage = {
  items: GoalProblem[]; page: number; size: number; totalItems: number; totalPages: number
  totalMatching: number; counted: number
}

export function listGoalProblems(goalId: number, filter: GoalProblemFilter, sort: GoalProblemSort, page: number, signal?: AbortSignal) {
  const query = new URLSearchParams({ filter, sort, page: String(page) })
  return request<GoalProblemPage>(`goals/${goalId}/problems?${query}`, { signal })
}

export type PlannerView = 'DAY' | 'WEEK'
export type TaskState = 'PLANNED' | 'COMPLETED' | 'SKIPPED'
export type Task = {
  id: number
  date: string
  state: TaskState
  problem: { id: number; leetcodeId: number; title: string; status: ProgressStatus; solvedDate: string | null }
}
export type Counts = { planned: number; completed: number; skipped: number }
export type Day = { date: string; items: Task[]; counts: Counts }
export type PlannerCalendar = {
  view: PlannerView
  date: string
  today: string
  startDate: string
  endDate: string
  days: Day[]
  counts: Counts
}

export function listGoals(signal?: AbortSignal) {
  return request<Goal[]>('goals', { signal })
}

export function saveGoal(input: GoalInput, id?: number) {
  return request<Goal>(id === undefined ? 'goals' : `goals/${id}`, {
    method: id === undefined ? 'POST' : 'PUT',
    body: JSON.stringify(input),
  })
}

export function deleteGoal(id: number) {
  return request<void>(`goals/${id}`, { method: 'DELETE' })
}

export function getCalendar(view: PlannerView, date: string, signal?: AbortSignal) {
  const query = new URLSearchParams({ view })
  if (date) query.set('date', date)
  return request<PlannerCalendar>(`planner?${query}`, { signal })
}

export function scheduleProblem(problemId: number, date: string) {
  return request<Task>('planner', { method: 'POST', body: JSON.stringify({ problemId, date }) })
}

export function moveTask(id: number, date: string, carryForward: boolean) {
  return request<Task>(carryForward ? `planner/${id}/carry-forward` : `planner/${id}`, {
    method: carryForward ? 'POST' : 'PUT',
    body: JSON.stringify({ date }),
  })
}

export function skipTask(id: number, skipped: boolean) {
  return request<Task>(`planner/${id}/skip`, { method: 'PATCH', body: JSON.stringify({ skipped }) })
}

export function deleteTask(id: number) {
  return request<void>(`planner/${id}`, { method: 'DELETE' })
}
