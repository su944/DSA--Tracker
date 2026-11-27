import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { difficulties, statuses } from '../catalog/api'
import { LoadMessage } from '../catalog/components'
import { useResource } from '../catalog/useResource'
import {
  goalProblemFilters, goalProblemSorts, listGoalProblems,
  type Goal, type GoalProblem, type GoalProblemFilter, type GoalProblemSort,
} from './api'
import { displayDate } from './dates'

export function GoalProblems({ goal }: { goal: Goal }) {
  const [expanded, setExpanded] = useState(false)
  const [matching, setMatching] = useState<number>()
  return (
    <details className="goal-problems" onToggle={(event) => setExpanded(event.currentTarget.open)}>
      <summary>{matching === undefined ? 'Problems' : `Problems (${matching})`}</summary>
      {expanded && <GoalProblemList goal={goal} onMatching={setMatching} />}
    </details>
  )
}

function GoalProblemList({ goal, onMatching }: { goal: Goal; onMatching: (count: number) => void }) {
  const [filter, setFilter] = useState<GoalProblemFilter>('ALL')
  const [sort, setSort] = useState<GoalProblemSort>('LEETCODE_ID')
  const [page, setPage] = useState(0)
  const load = useCallback(async (signal: AbortSignal) => {
    const data = await listGoalProblems(goal.id, filter, sort, page, signal)
    if (!signal.aborted) onMatching(data.totalMatching)
    return data
  }, [goal.id, filter, sort, page, onMatching])
  const { result, retry } = useResource(load)

  return (
    <>
      <div className="catalog-form-grid goal-problem-controls">
        <label>Show problems<select value={filter} onChange={(event) => {
          setFilter(event.target.value as GoalProblemFilter)
          setPage(0)
        }}>
          {Object.entries(goalProblemFilters).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select></label>
        <label>Sort by<select value={sort} onChange={(event) => {
          setSort(event.target.value as GoalProblemSort)
          setPage(0)
        }}>
          {Object.entries(goalProblemSorts).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select></label>
      </div>
      {result.state !== 'ready' ? (
        <LoadMessage label="goal problems" retry={retry} error={result.state === 'error' ? result.error : undefined} />
      ) : (
        <>
          <p className="body-text" role="status">
            Target: {goal.progress.targetCount} · Matching problems: {result.data.totalMatching} · Counted toward goal: {result.data.counted}
            {filter !== 'ALL' && ` · Filtered results: ${result.data.totalItems}`}
          </p>
          {result.data.items.length ? (
            <ul className="catalog-list" aria-label="Goal problems">
              {result.data.items.map((problem) => <GoalProblemRow key={problem.id} problem={problem} />)}
            </ul>
          ) : (
            <p className="body-text">{result.data.totalMatching === 0
              ? 'No catalog problems match this goal.' : 'No problems on this page for the selected filter.'}</p>
          )}
          {(result.data.totalPages > 1 || page > 0) && (
            <div className="catalog-pagination" aria-label="Goal problem pagination">
              <button type="button" className="catalog-button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
              <span>Page {page + 1} of {Math.max(1, result.data.totalPages)}</span>
              <button type="button" className="catalog-button" disabled={page + 1 >= result.data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
            </div>
          )}
        </>
      )}
    </>
  )
}

export function GoalProblemRow({ problem }: { problem: GoalProblem }) {
  return (
    <li className="problem-row goal-problem-row">
      <div className="problem-name">
        <Link to={`/problems/${problem.id}`} className="catalog-link">{problem.leetcodeId}. {problem.title}</Link>
        {problem.topics.length > 0 && <div className="topic-tags">{problem.topics.map((topic) =>
          <span key={topic.id}>{topic.name}</span>)}</div>}
      </div>
      <span className={`catalog-badge difficulty-${problem.difficulty.toLowerCase()}`}>{difficulties[problem.difficulty]}</span>
      <span className={`catalog-badge progress-${problem.status.toLowerCase()}`}>{statuses[problem.status]}</span>
      <div className="body-text goal-problem-result">
        {problem.solvedDate && <p>Solved <time dateTime={problem.solvedDate}>{displayDate(problem.solvedDate)}</time></p>}
        {problem.countsTowardGoal && <p>Counts toward goal</p>}
        {problem.solvedOutsideRange && <p>Outside goal range</p>}
      </div>
    </li>
  )
}
