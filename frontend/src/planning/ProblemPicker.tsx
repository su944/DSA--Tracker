import { useCallback, useState } from 'react'
import { listProblems } from '../catalog/api'
import { asError, FormError, LoadMessage } from '../catalog/components'
import { useResource } from '../catalog/useResource'
import { scheduleProblem } from './api'
import { displayDate } from './dates'

export function ProblemPicker({ date, scheduledIds, onClose, onSaved }: {
  date: string; scheduledIds: number[]; onClose: () => void; onSaved: () => void
}) {
  const [query, setQuery] = useState({ text: '', page: 0 })
  const load = useCallback((signal: AbortSignal) => listProblems(new URLSearchParams({
    q: query.text, page: String(query.page), size: '10',
  }).toString(), signal), [query])
  const problems = useResource(load)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<Error | null>(null)

  async function schedule(problemId: number) {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      await scheduleProblem(problemId, date)
      onSaved()
    } catch (failure) {
      setError(asError(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="catalog-panel catalog-editor" aria-labelledby="schedule-heading">
      <h2 id="schedule-heading">Schedule for {displayDate(date)}</h2>
      <FormError error={error} />
      <form className="catalog-actions planning-picker-search" onSubmit={(event) => {
        event.preventDefault()
        setError(null)
        setQuery({ text: String(new FormData(event.currentTarget).get('q')).trim(), page: 0 })
      }}>
        <label>Find a problem<input name="q" type="search" maxLength={255} placeholder="LeetCode ID or title" disabled={busy} /></label>
        <button type="submit" className="catalog-button" disabled={busy}>Search</button>
        <button type="button" className="catalog-button" disabled={busy} onClick={onClose}>Close picker</button>
      </form>
      {problems.result.state !== 'ready' ? (
        <LoadMessage label="problems" retry={problems.retry} error={problems.result.state === 'error' ? problems.result.error : undefined} />
      ) : (
        <>
          {problems.result.data.items.length ? (
            <ul className="catalog-list planning-picker-results">
              {problems.result.data.items.map((problem) => {
                const scheduled = scheduledIds.includes(problem.id)
                return (
                  <li key={problem.id}>
                    <span>#{problem.leetcodeId} <strong>{problem.title}</strong></span>
                    <button type="button" className="catalog-button" disabled={busy || scheduled}
                      aria-label={scheduled ? `${problem.title} is already scheduled` : `Schedule ${problem.title}`}
                      onClick={() => schedule(problem.id)}>{scheduled ? 'Already scheduled' : 'Schedule'}</button>
                  </li>
                )
              })}
            </ul>
          ) : <p className="body-text">No problems found. Try another search or add a problem in the library.</p>}
          {problems.result.data.totalPages > 1 && (
            <div className="catalog-actions catalog-pagination">
              <button type="button" className="catalog-button" disabled={busy || query.page === 0}
                onClick={() => setQuery({ ...query, page: query.page - 1 })}>Previous results</button>
              <span>Page {problems.result.data.page + 1} of {problems.result.data.totalPages}</span>
              <button type="button" className="catalog-button" disabled={busy || query.page + 1 >= problems.result.data.totalPages}
                onClick={() => setQuery({ ...query, page: query.page + 1 })}>Next results</button>
            </div>
          )}
        </>
      )}
    </section>
  )
}
