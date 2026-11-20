import { useCallback } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { difficulties } from '../catalog/api'
import { LoadMessage } from '../catalog/components'
import { useResource } from '../catalog/useResource'
import { displayDate } from '../planning/dates'
import { ActivityChart, DifficultyChart, TopicChart } from './Charts'
import { getAnalytics, ranges, type Range, type RecentProblem } from './api'

export function AnalyticsPage() {
  return <><h1>Analytics</h1><AnalyticsPanels /></>
}

export function AnalyticsPanels() {
  const [params, setParams] = useSearchParams()
  const value = params.get('range')
  const range = value && Object.hasOwn(ranges, value) ? value as Range : '30'
  const load = useCallback((signal: AbortSignal) => getAnalytics(range, signal), [range])
  const { result, retry } = useResource(load)

  return (
    <section className="report-section" aria-label="Practice analytics">
      <div className="report-header">
        <h2>Practice analytics</h2>
        <label>Date range<select value={range} onChange={(event) => {
          const next = new URLSearchParams(params)
          next.set('range', event.target.value)
          setParams(next)
        }}>
          {Object.entries(ranges).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
        </select></label>
      </div>
      {result.state === 'ready' ? (
        <div className="report-grid">
          <ActivityChart key={range} activity={result.data.activity} />
          <TopicChart topics={result.data.topics} filtered={range !== 'all'} />
          <DifficultyChart values={result.data.difficulty} />
          <RecentList title="Recently solved" label="solved" problems={result.data.recent.solved} />
          <RecentList title="Recently modified" label="modified" problems={result.data.recent.modified} />
        </div>
      ) : <LoadMessage label="analytics" retry={retry} error={result.state === 'error' ? result.error : undefined} />}
    </section>
  )
}

function RecentList({ title, label, problems }: { title: string; label: 'solved' | 'modified'; problems: RecentProblem[] }) {
  return (
    <section className="catalog-panel report-card" aria-labelledby={`recent-${label}-heading`}>
      <h3 id={`recent-${label}-heading`}>{title}</h3>
      {problems.length === 0 ? <p className="body-text">No recently {label} problems in this range.</p> : (
        <ul className="catalog-list report-recent">
          {problems.map((problem) => {
            const date = label === 'solved' ? problem.solvedDate : problem.modifiedDate
            return (
              <li key={problem.id}>
                <div>
                  <Link className="catalog-link" to={`/problems/${problem.id}`}>{problem.title}</Link>
                  <p className="planning-meta">#{problem.leetcodeId} · {difficulties[problem.difficulty]}</p>
                </div>
                {date && <time dateTime={date}>{displayDate(date)}</time>}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
