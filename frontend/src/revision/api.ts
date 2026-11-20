import { request } from '../catalog/api'

export const revisionFilters = {
  DUE_TODAY: 'Due Today', OVERDUE: 'Overdue', UPCOMING: 'Upcoming', COMPLETED: 'Completed', CANCELLED: 'Cancelled',
} as const
export type RevisionFilter = keyof typeof revisionFilters
export type RevisionCounts = { dueToday: number; overdue: number; upcoming: number }
export type RevisionTask = {
  id: number; problemId: number; leetcodeId: number; title: string; scheduledDate: string;
  status: 'SCHEDULED' | 'COMPLETED' | 'CANCELLED'; completedAt: string | null; createdAt: string; updatedAt: string
}
export type RevisionQueue = { today: string; counts: RevisionCounts; items: RevisionTask[] }

export function getRevisions(filter: RevisionFilter, signal?: AbortSignal) {
  return request<RevisionQueue>(`revisions?filter=${filter}`, { signal })
}

export function scheduleRevision(problemId: number, date: string) {
  return request<RevisionTask>('revisions', { method: 'POST', body: JSON.stringify({ problemId, date }) })
}

export function rescheduleRevision(id: number, date: string) {
  return request<RevisionTask>(`revisions/${id}`, { method: 'PUT', body: JSON.stringify({ date }) })
}

export function finishRevision(id: number, action: 'complete' | 'cancel') {
  return request<RevisionTask>(`revisions/${id}/${action}`, { method: 'POST' })
}
