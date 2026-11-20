import { Activity, RefreshCw } from 'lucide-react'
import { Link } from 'react-router-dom'
import { LoadMessage } from '../catalog/components'
import { useResource } from '../catalog/useResource'
import { goalTypes } from '../planning/api'
import { dateRange, displayDate } from '../planning/dates'
import { GoalProgressMeter } from '../planning/GoalProgressMeter'
import { useBackendHealth, type BackendHealth } from '../useBackendHealth'
import { AnalyticsPanels } from './AnalyticsPanels'
import { getSummary, getToday, type Summary, type Today } from './api'

const connection = {
  checking: { label: 'Checking backend', detail: 'Checking the local API and database.' },
  online: { label: 'Backend connected', detail: 'The local API and SQLite are responding.' },
  unavailable: { label: 'Database unavailable', detail: 'The local API responded, but its SQLite check failed.' },
  offline: { label: 'Backend unreachable', detail: 'Start the Spring Boot backend, then check the connection again.' },
} satisfies Record<BackendHealth, { label: string; detail: string }>

function LocalConnection() {
  const { health, refresh } = useBackendHealth()
  const { label, detail } = connection[health]

  return (
    <section className="connection-card" aria-labelledby="connection-heading">
      <div className="connection-icon"><Activity size={23} aria-hidden="true" /></div>
      <div className="connection-copy">
        <h2 id="connection-heading">Local connection</h2>
        <div role="status" className="connection-status">
          <p className="status-label"><span className={`status-dot ${health}`} aria-hidden="true" />{label}</p>
          <p className="body-text">{detail}</p>
        </div>
      </div>
      <button type="button" className="check-button" onClick={refresh} disabled={health === 'checking'}>
        <RefreshCw size={16} aria-hidden="true" />
        Check again
      </button>
    </section>
  )
}

export function DashboardPage() {
  const summary = useResource(getSummary)
  const today = useResource(getToday)
  return (
    <>
      <h1>Dashboard</h1>
      {summary.result.state === 'ready'
        ? <SummaryCards summary={summary.result.data} />
        : <LoadMessage label="problem totals" retry={summary.retry}
          error={summary.result.state === 'error' ? summary.result.error : undefined} />}
      {today.result.state === 'ready'
        ? <TodayPanels today={today.result.data} />
        : <LoadMessage label="today’s plan and goals" retry={today.retry}
          error={today.result.state === 'error' ? today.result.error : undefined} />}
      <AnalyticsPanels />
      <LocalConnection />
    </>
  )
}

function SummaryCards({ summary }: { summary: Summary }) {
  const cards = [
    { name: 'Total problems', value: summary.total, style: 'total' },
    { name: 'Solved', value: summary.solved, style: 'solved' },
    { name: 'Attempted', value: summary.attempted, style: 'attempted' },
    { name: 'Not Started', value: summary.notStarted, style: 'pending' },
  ]
  return (
    <dl className="report-summary" aria-label="Problem totals">
      {cards.map((card) => (
        <div key={card.name} className={`report-stat report-stat-${card.style}`}>
          <dt>{card.name}</dt>
          <dd>{card.value}</dd>
        </div>
      ))}
    </dl>
  )
}

const taskLabels = { PLANNED: 'Planned', COMPLETED: 'Completed', SKIPPED: 'Skipped' } as const

function TodayPanels({ today }: { today: Today }) {
  const { plan, goals } = today
  return (
    <div className="report-grid">
      <section className="catalog-panel report-card" aria-labelledby="today-plan-heading">
        <div className="report-header">
          <div>
            <h2 id="today-plan-heading">Today’s study plan</h2>
            <p className="body-text">{displayDate(today.date)}</p>
          </div>
          <Link to={`/planner?date=${today.date}`} className="catalog-link">Open planner</Link>
        </div>
        <p className="planning-counts">
          <span>{plan.counts.planned} planned</span><span>{plan.counts.completed} completed</span>
          {plan.counts.skipped > 0 && <span>{plan.counts.skipped} skipped</span>}
        </p>
        {plan.items.length === 0 ? <p className="body-text">No problems scheduled today.</p> : (
          <ul className="catalog-list report-recent">
            {plan.items.map((task) => (
              <li key={task.id}>
                <Link to={`/problems/${task.problem.id}`} className="catalog-link">{task.problem.title}</Link>
                <span className={`catalog-badge task-${task.state.toLowerCase()}`}>{taskLabels[task.state]}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
      <section className="catalog-panel report-card" aria-labelledby="active-goals-heading">
        <div className="report-header">
          <h2 id="active-goals-heading">Active goals</h2>
          <Link to="/goals" className="catalog-link">Open goals</Link>
        </div>
        {goals.length === 0 ? <p className="body-text">No goals cover today.</p> : (
          <ul className="catalog-list report-goals">
            {goals.map((goal) => (
              <li key={goal.id}>
                <p className="planning-meta">{goalTypes[goal.type]} · {goal.topic?.name ?? 'All topics'}</p>
                <h3>{goal.name}</h3>
                <p className="body-text">{dateRange(goal.startDate, goal.endDate)}</p>
                <GoalProgressMeter goal={goal} />
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
