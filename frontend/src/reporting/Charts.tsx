import { useState } from 'react'
import { Link } from 'react-router-dom'
import { difficulties } from '../catalog/api'
import { dateRange, displayDate } from '../planning/dates'
import type { Activity, DifficultyStats, TopicStats } from './api'

function percent(count: number, total: number) {
  return total ? 100 * count / total : 0
}

export function TopicChart({ topics, filtered }: { topics: TopicStats[]; filtered: boolean }) {
  return (
    <section className="catalog-panel report-card" aria-labelledby="topic-chart-heading">
      <h3 id="topic-chart-heading">Topic progress</h3>
      {topics.length === 0 ? <p className="body-text">No topics yet.</p> : (
        <>
          <div className="report-legend">
            <span><i className="report-swatch report-solved" aria-hidden="true" />{filtered ? 'Solved in range' : 'Solved'}</span>
            {filtered && <span><i className="report-swatch report-outside" aria-hidden="true" />Solved outside range</span>}
            <span><i className="report-swatch report-remaining" aria-hidden="true" />Remaining</span>
          </div>
          <ul className="report-bars">
            {topics.map((topic) => {
              const outsideRange = topic.total - topic.solved - topic.remaining
              return (
                <li key={topic.topicId}>
                  <div className="report-bar-label">
                    <Link to={`/problems?topic=${topic.topicId}`} className="catalog-link">{topic.name}</Link>
                    <span>{topic.solved} solved · {topic.remaining} remaining{outsideRange > 0 && ` · ${outsideRange} outside range`}</span>
                  </div>
                  <div className="report-track" aria-hidden="true">
                    <span className="report-solved" style={{ width: `${percent(topic.solved, topic.total)}%` }} />
                    <span className="report-outside" style={{ width: `${percent(outsideRange, topic.total)}%` }} />
                  </div>
                </li>
              )
            })}
          </ul>
        </>
      )}
    </section>
  )
}

export function DifficultyChart({ values }: { values: DifficultyStats[] }) {
  const total = values.reduce((sum, value) => sum + value.solved, 0)
  return (
    <section className="catalog-panel report-card" aria-labelledby="difficulty-chart-heading">
      <h3 id="difficulty-chart-heading">Solved by difficulty</h3>
      {total === 0 ? <p className="body-text">No solved problems in this range.</p> : (
        <ul className="report-bars">
          {Object.entries(difficulties).map(([key, name]) => {
            const count = values.find((value) => value.difficulty === key)?.solved ?? 0
            const share = percent(count, total)
            return (
              <li key={key}>
                <div className="report-bar-label"><span>{name}</span><span>{count} solved · {Math.round(share)}%</span></div>
                <div className="report-track" aria-hidden="true">
                  <span className={`report-fill-${key.toLowerCase()}`} style={{ width: `${share}%` }} />
                </div>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}

export function ActivityChart({ activity }: { activity: Activity }) {
  const [selectedDate, setSelectedDate] = useState<string | null>(null)
  const { days } = activity
  const peak = days.reduce((highest, day) => Math.max(highest, day.solved), 0)
  const total = days.reduce((sum, day) => sum + day.solved, 0)
  const selected = days.find((day) => day.date === selectedDate)
  return (
    <section className="catalog-panel report-card report-activity" aria-labelledby="activity-chart-heading">
      <h3 id="activity-chart-heading">Solved activity</h3>
      {days.length === 0 ? <p className="body-text">No dated solves in this range.</p> : (
        <>
          <div className="report-bar-label">
            <p>{total} solved · {activity.startDate ? dateRange(activity.startDate, activity.endDate) : 'Dates with solves'}</p>
            <p role="status">{selected
              ? `${displayDate(selected.date)} · ${selected.solved} solved`
              : 'Select a day for its count.'}</p>
          </div>
          <div className="report-activity-plot" role="group" aria-label="Daily solved counts">
            {days.map((day) => (
              <button key={day.date} type="button" className="report-activity-day"
                aria-label={`${displayDate(day.date)}: ${day.solved} solved`} aria-pressed={day.date === selectedDate}
                onClick={() => setSelectedDate(day.date)}>
                <span aria-hidden="true" style={{ height: `max(2px, ${percent(day.solved, peak)}%)` }} />
              </button>
            ))}
          </div>
          <div className="report-axis"><span>{displayDate(days[0].date)}</span><span>{displayDate(days[days.length - 1].date)}</span></div>
        </>
      )}
    </section>
  )
}
