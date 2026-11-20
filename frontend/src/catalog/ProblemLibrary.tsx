import { useCallback } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { difficulties, listProblems, listTopics, statuses } from './api'
import { LoadMessage } from './components'
import { useResource } from './useResource'

const defaultSort = 'leetcodeId,asc'

export function ProblemLibrary() {
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  const query = params.toString()
  const load = useCallback((signal: AbortSignal) => listProblems(query, signal), [query])
  const problems = useResource(load)
  const topics = useResource(listTopics)

  function changePage(page: number) {
    const updated = new URLSearchParams(params)
    updated.set('page', String(page))
    setParams(updated)
  }

  return (
    <>
      <header className="catalog-heading">
        <div><h1>Problems</h1><p className="body-text">Search and filter your problem library.</p></div>
        <Link className="catalog-button primary" to="/problems/new">Add problem</Link>
      </header>
      {typeof location.state?.message === 'string' && <p className="catalog-success" role="status">{location.state.message}</p>}
      <form className="catalog-panel catalog-filters" key={query} onSubmit={(event) => {
        event.preventDefault()
        const selected = new FormData(event.currentTarget)
        const next = new URLSearchParams()
        for (const [name, value] of selected) {
          if (typeof value === 'string' && value.trim() && !(name === 'sort' && value === defaultSort)) {
            next.append(name, value.trim())
          }
        }
        setParams(next)
      }}>
        <label>Search<input name="q" type="search" maxLength={255} defaultValue={params.get('q') ?? ''} placeholder="LeetCode ID or title" /></label>
        <label>Topic<select key={topics.result.state} name="topic" defaultValue={params.get('topic') ?? ''} disabled={topics.result.state !== 'ready'}>
          <option value="">All topics</option>
          {topics.result.state === 'ready' && topics.result.data.map((topic) => <option key={topic.id} value={topic.id}>{topic.name}</option>)}
        </select></label>
        <label>Difficulty<select name="difficulty" defaultValue={params.get('difficulty') ?? ''}>
          <option value="">All difficulties</option>
          {Object.entries(difficulties).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select></label>
        <label>Status<select name="status" defaultValue={params.get('status') ?? ''}>
          <option value="">All statuses</option>
          {Object.entries(statuses).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select></label>
        <label>Sort by<select name="sort" defaultValue={params.get('sort') ?? defaultSort}>
          <option value="leetcodeId,asc">LeetCode ID, low to high</option>
          <option value="leetcodeId,desc">LeetCode ID, high to low</option>
          <option value="title,asc">Title, A to Z</option>
          <option value="updatedAt,desc">Recently edited</option>
        </select></label>
        <div className="catalog-actions">
          <button className="catalog-button primary" type="submit">Apply filters</button>
          {query && <button className="catalog-button" type="button" onClick={() => setParams({})}>Clear</button>}
        </div>
      </form>
      {topics.result.state === 'error' && <LoadMessage error={topics.result.error} retry={topics.retry} label="topics" />}
      {problems.result.state !== 'ready' ? (
        <LoadMessage error={problems.result.state === 'error' ? problems.result.error : undefined} retry={problems.retry} label="problems" />
      ) : (
        <section className="catalog-panel" aria-label="Problem results">
          <p className="catalog-count" role="status">{problems.result.data.totalItems} {problems.result.data.totalItems === 1 ? 'problem' : 'problems'}</p>
          {problems.result.data.items.length ? (
            <ul className="catalog-list">
              {problems.result.data.items.map((problem) => (
                <li key={problem.id} className="problem-row">
                  <div className="problem-name">
                    <Link to={`/problems/${problem.id}`} className="catalog-link">{problem.title}</Link>
                    <span className="body-text">#{problem.leetcodeId}</span>
                  </div>
                  <div className="topic-tags">{problem.topics.map((topic) => <span key={topic.id}>{topic.name}</span>)}</div>
                  <span className={`catalog-badge difficulty-${problem.difficulty.toLowerCase()}`}>{difficulties[problem.difficulty]}</span>
                  <span className={`catalog-badge progress-${problem.progress.status.toLowerCase()}`}>{statuses[problem.progress.status]}</span>
                </li>
              ))}
            </ul>
          ) : <p className="body-text">{query ? 'No problems on this page. Try clearing a filter or choosing an earlier page.' : 'No problems yet. Add a problem to start your library.'}</p>}
          {(problems.result.data.totalPages > 1 || problems.result.data.page > 0) && (
            <div className="catalog-pagination" aria-label="Problem pagination">
              <button type="button" className="catalog-button" disabled={problems.result.data.page === 0}
                onClick={() => changePage(Number(params.get('page') ?? 0) - 1)}>Previous</button>
              <span>Page {problems.result.data.page + 1} of {Math.max(1, problems.result.data.totalPages)}</span>
              <button type="button" className="catalog-button" disabled={problems.result.data.page + 1 >= problems.result.data.totalPages}
                onClick={() => changePage(Number(params.get('page') ?? 0) + 1)}>Next</button>
            </div>
          )}
        </section>
      )}
    </>
  )
}
