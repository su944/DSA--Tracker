import { request, type Difficulty, type ProgressStatus } from '../catalog/api'
import type { Day, Goal } from '../planning/api'

export const ranges = { '7': 'Last 7 days', '30': 'Last 30 days', '90': 'Last 90 days', all: 'All time' } as const
export type Range = keyof typeof ranges
export type Summary = { total: number; solved: number; attempted: number; notStarted: number }
export type Today = { date: string; plan: Day; goals: Goal[] }
export type TopicStats = { topicId: number; name: string; total: number; solved: number; remaining: number }
export type DifficultyStats = { difficulty: Difficulty; solved: number }
export type ActivityDay = { date: string; solved: number }
export type Activity = { startDate: string | null; endDate: string; days: ActivityDay[] }
export type RecentProblem = {
  id: number; leetcodeId: number; title: string; difficulty: Difficulty; status: ProgressStatus;
  solvedDate: string | null; modifiedAt: string; modifiedDate: string
}
export type Recent = { solved: RecentProblem[]; modified: RecentProblem[] }

export function getSummary(signal?: AbortSignal) {
  return request<Summary>('dashboard/summary', { signal })
}

export function getToday(signal?: AbortSignal) {
  return request<Today>('dashboard/today', { signal })
}

export async function getAnalytics(range: Range, signal: AbortSignal) {
  const query = new URLSearchParams({ range })
  const [topics, difficulty, activity, recent] = await Promise.all([
    request<TopicStats[]>(`analytics/topics?${query}`, { signal }),
    request<DifficultyStats[]>(`analytics/difficulty?${query}`, { signal }),
    request<Activity>(`analytics/activity?${query}`, { signal }),
    request<Recent>(`analytics/recent?${query}`, { signal }),
  ])
  return { topics, difficulty, activity, recent }
}
