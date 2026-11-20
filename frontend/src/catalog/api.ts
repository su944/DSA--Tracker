export const difficulties = { EASY: 'Easy', MEDIUM: 'Medium', HARD: 'Hard' } as const
export const statuses = { NOT_STARTED: 'Not Started', ATTEMPTED: 'Attempted', SOLVED: 'Solved' } as const

export type Difficulty = keyof typeof difficulties
export type ProgressStatus = keyof typeof statuses
export type Topic = { id: number; name: string; createdAt: string }
export type Progress = {
  status: ProgressStatus
  attemptedDate: string | null
  solvedDate: string | null
  comments: string
  updatedAt: string
}
export type Problem = {
  id: number
  leetcodeId: number
  title: string
  slug: string
  url: string
  difficulty: Difficulty
  createdAt: string
  updatedAt: string
  topics: Topic[]
  progress: Progress
}
export type ProblemInput = Pick<Problem, 'leetcodeId' | 'title' | 'url' | 'difficulty'> & { topicIds: number[] }
export type ProgressPatch = Partial<Omit<Progress, 'updatedAt'>>
export type ProblemPage = { items: Problem[]; page: number; size: number; totalItems: number; totalPages: number }

export class CatalogError extends Error {
  constructor(message: string, readonly fields: Record<string, string>) {
    super(message)
  }
}

export async function request<T>(path: string, { timeoutMs = 10_000, ...options }:
  RequestInit & { timeoutMs?: number } = {}): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json')
  if (typeof options.body === 'string') headers.set('Content-Type', 'application/json')
  const timeout = AbortSignal.timeout(timeoutMs)
  const signal = options.signal ? AbortSignal.any([options.signal, timeout]) : timeout

  let response: Response
  try {
    response = await fetch(`/api/${path}`, { ...options, headers, signal })
  } catch (error) {
    if (options.signal?.aborted) throw error
    if (timeout.aborted) {
      const message = options.method && options.method !== 'GET'
        ? 'The backend took too long to respond. This operation may still be running. Check the result before retrying.'
        : 'The local backend took too long to respond. Try again.'
      throw new CatalogError(message, {})
    }
    throw new CatalogError('Could not connect to the local backend. Check that it is running and try again.', {})
  }
  if (!response.ok) {
    const error: { message?: string; fields?: Record<string, string> } | null = await response.json().catch(() => null)
    throw new CatalogError(error?.message ?? `Request failed (HTTP ${response.status}).`, error?.fields ?? {})
  }
  return response.status === 204 ? undefined as T : await response.json() as T
}

export function listProblems(query: string, signal?: AbortSignal) {
  return request<ProblemPage>(`problems?${query}`, { signal })
}

export function getProblem(id: string, signal?: AbortSignal) {
  return request<Problem>(`problems/${encodeURIComponent(id)}`, { signal })
}

export function saveProblem(input: ProblemInput, id?: number) {
  return request<Problem>(id === undefined ? 'problems' : `problems/${id}`, {
    method: id === undefined ? 'POST' : 'PUT',
    body: JSON.stringify(input),
  })
}

export function deleteProblem(id: number) {
  return request<void>(`problems/${id}`, { method: 'DELETE' })
}

export function saveProgress(id: number, input: ProgressPatch) {
  return request<Progress>(`problems/${id}/progress`, { method: 'PATCH', body: JSON.stringify(input) })
}

export function listTopics(signal?: AbortSignal) {
  return request<Topic[]>('topics', { signal })
}

export function saveTopic(name: string, id?: number) {
  return request<Topic>(id === undefined ? 'topics' : `topics/${id}`, {
    method: id === undefined ? 'POST' : 'PUT',
    body: JSON.stringify({ name }),
  })
}

export function deleteTopic(id: number) {
  return request<void>(`topics/${id}`, { method: 'DELETE' })
}
