import { useEffect, useState } from 'react'

type Result<T> = { state: 'loading' } | { state: 'ready'; data: T } | { state: 'error'; error: string }

export function useResource<T>(load: (signal: AbortSignal) => Promise<T>) {
  const [attempt, setAttempt] = useState(0)
  const [completed, setCompleted] = useState<{
    load: typeof load; attempt: number; result: Result<T>
  }>()

  useEffect(() => {
    const request = new AbortController()
    load(request.signal).then(
      (data) => {
        if (!request.signal.aborted) setCompleted({ load, attempt, result: { state: 'ready', data } })
      },
      (error: unknown) => {
        if (!request.signal.aborted) {
          setCompleted({ load, attempt, result: {
            state: 'error', error: error instanceof Error ? error.message : 'The request failed.',
          } })
        }
      },
    )
    return () => request.abort()
  }, [load, attempt])

  const result: Result<T> = completed?.load === load && completed.attempt === attempt
    ? completed.result : { state: 'loading' }
  return { result, retry: () => setAttempt((current) => current + 1) }
}
