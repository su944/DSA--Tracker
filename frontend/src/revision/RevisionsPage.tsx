import { useCallback, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { asError, FormError, LoadMessage } from '../catalog/components'
import { useResource } from '../catalog/useResource'
import { displayDate, shiftDate } from '../planning/dates'
import { ProblemPicker } from '../planning/ProblemPicker'
import {
  finishRevision, getRevisions, rescheduleRevision, revisionFilters, scheduleRevision,
  type RevisionFilter, type RevisionTask,
} from './api'

const emptyMessages: Record<RevisionFilter, string> = {
  DUE_TODAY: 'No revisions due today.',
  OVERDUE: 'No overdue revisions.',
  UPCOMING: 'No upcoming revisions.',
  COMPLETED: 'No completed revisions.',
  CANCELLED: 'No cancelled revisions.',
}

export function RevisionsPage() {
  const [params, setParams] = useSearchParams()
  const selected = params.get('filter')
  const filter = selected && Object.hasOwn(revisionFilters, selected) ? selected as RevisionFilter : 'DUE_TODAY'
  const load = useCallback((signal: AbortSignal) => getRevisions(filter, signal), [filter])
  const queue = useResource(load)
  const [schedule, setSchedule] = useState(false)
  const [date, setDate] = useState('')
  const [draftDate, setDraftDate] = useState('')
  const [message, setMessage] = useState('')

  function changed(text: string) {
    setMessage(text)
    queue.retry()
  }

  const data = queue.result.state === 'ready' ? queue.result.data : null
  return (
    <>
      <header className="catalog-heading">
        <div><h1>Revisions</h1><p className="body-text">Review previously solved problems.</p></div>
        <button className="catalog-button primary" type="button" disabled={!data || schedule} onClick={() => {
          if (data) {
            const initial = shiftDate(data.today, 1) || data.today
            setDate(initial)
            setDraftDate(initial)
          }
          setSchedule(true)
          setMessage('')
        }}>Schedule revision</button>
      </header>
      {message && <p className="catalog-success" role="status">{message}</p>}
      {queue.result.state !== 'ready' || !data ? (
        <LoadMessage label="revision queue" retry={queue.retry}
          error={queue.result.state === 'error' ? queue.result.error : undefined} />
      ) : (
        <>
          <nav className="catalog-panel catalog-actions" aria-label="Revision filters">
            {Object.entries(revisionFilters).map(([value, label]) => {
              const count = { DUE_TODAY: data.counts.dueToday, OVERDUE: data.counts.overdue, UPCOMING: data.counts.upcoming }[value]
              return <button key={value} className={`catalog-button ${value === filter ? 'primary' : ''}`}
                type="button" aria-pressed={value === filter} onClick={() => {
                  setParams({ filter: value })
                  setMessage('')
                }}>{label}{count === undefined ? '' : ` (${count})`}</button>
            })}
          </nav>
          {schedule && (
            <>
              <form className="catalog-panel catalog-editor catalog-actions tracker-date-form" onSubmit={(event) => {
                event.preventDefault()
                setDate(draftDate)
              }}>
                <label>Revision date<input type="date" min="0001-01-01" max="9999-12-31" required value={draftDate}
                  onChange={(event) => setDraftDate(event.target.value)} /></label>
                <button type="submit" className="catalog-button" disabled={draftDate === date}>Use date</button>
                <button className="catalog-button" type="button" onClick={() => setSchedule(false)}>Cancel scheduling</button>
              </form>
              {date && draftDate === date && <ProblemPicker key={date} date={date} status="SOLVED" onSchedule={scheduleRevision}
                scheduledIds={data.items.filter((item) => item.status === 'SCHEDULED' && item.scheduledDate === date).map((item) => item.problemId)}
                onClose={() => setSchedule(false)} onSaved={() => {
                  setSchedule(false)
                  setParams({ filter: date < data.today ? 'OVERDUE' : date === data.today ? 'DUE_TODAY' : 'UPCOMING' })
                  changed('Revision scheduled.')
                }} />}
            </>
          )}
          <section className="catalog-panel" aria-label={revisionFilters[filter]}>
            {data.items.length === 0 ? <p className="body-text">{emptyMessages[filter]}</p> : (
              <ul className="catalog-list">{data.items.map((task) => <RevisionRow key={task.id} task={task} onChanged={changed} />)}</ul>
            )}
          </section>
        </>
      )}
    </>
  )
}

function RevisionRow({ task, onChanged }: { task: RevisionTask; onChanged: (message: string) => void }) {
  const [mode, setMode] = useState<'date' | 'cancel' | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<Error | null>(null)

  function show(next: typeof mode) {
    setError(null)
    setMode(next)
  }

  async function mutate(action: () => Promise<unknown>, message: string) {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      await action()
      onChanged(message)
    } catch (failure) {
      setError(asError(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <li className="settings-row">
      <div>
        <Link to={`/problems/${task.problemId}`} className="catalog-link">{task.title}</Link>
        <p className="body-text">#{task.leetcodeId} · {displayDate(task.scheduledDate)}</p>
      </div>
      <div>
        <FormError error={error} />
        {task.status !== 'SCHEDULED' ? (
          <span className="catalog-badge">{task.status === 'COMPLETED' ? 'Completed' : 'Cancelled'}</span>
        ) : mode === 'date' ? (
          <form className="catalog-actions" onSubmit={(event) => {
            event.preventDefault()
            const date = String(new FormData(event.currentTarget).get('date'))
            void mutate(() => rescheduleRevision(task.id, date), 'Revision rescheduled.')
          }}>
            <label>New date<input type="date" name="date" required min="0001-01-01" max="9999-12-31" defaultValue={task.scheduledDate} disabled={busy} /></label>
            <button type="submit" className="catalog-button primary" disabled={busy}>Save date</button>
            <button type="button" className="catalog-button" disabled={busy} onClick={() => show(null)}>Keep date</button>
          </form>
        ) : mode === 'cancel' ? (
          <div className="catalog-actions">
            <button type="button" className="catalog-button danger" disabled={busy}
              onClick={() => mutate(() => finishRevision(task.id, 'cancel'), 'Revision cancelled.')}>Confirm cancellation</button>
            <button type="button" className="catalog-button" disabled={busy} onClick={() => show(null)}>Keep revision</button>
          </div>
        ) : (
          <div className="catalog-actions">
            <button type="button" className="catalog-button primary" disabled={busy}
              onClick={() => mutate(() => finishRevision(task.id, 'complete'), 'Revision completed. Next review scheduled in 7 days.')}>Complete</button>
            <button type="button" className="catalog-button" disabled={busy} onClick={() => show('date')}>Reschedule</button>
            <button type="button" className="catalog-button danger" disabled={busy} onClick={() => show('cancel')}>Cancel revision</button>
          </div>
        )}
      </div>
    </li>
  )
}
