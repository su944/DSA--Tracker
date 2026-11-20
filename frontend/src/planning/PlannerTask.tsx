import { useState } from 'react'
import { Link } from 'react-router-dom'
import { statuses } from '../catalog/api'
import { asError, FormError } from '../catalog/components'
import { deleteTask, moveTask, skipTask, type Task } from './api'
import { shiftDate } from './dates'

const labels = { PLANNED: 'Planned', COMPLETED: 'Completed', SKIPPED: 'Skipped' } as const

export function PlannerTask({ task, onChanged }: { task: Task; onChanged: (message: string) => void }) {
  const [mode, setMode] = useState<'move' | 'carry' | 'remove' | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const completed = task.state === 'COMPLETED'
  const skipped = task.state === 'SKIPPED'
  const followingDay = shiftDate(task.date, 1)

  function show(next: typeof mode) {
    setError(null)
    setMode(next)
  }

  async function run(action: () => Promise<unknown>, message: string) {
    if (busy) return
    setError(null)
    setBusy(true)
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
    <li className="planning-task">
      <div>
        <Link className="catalog-link" to={`/problems/${task.problem.id}`}>{task.problem.title}</Link>
        <p className="planning-meta">#{task.problem.leetcodeId} · {statuses[task.problem.status]}</p>
      </div>
      <p><span className={`catalog-badge task-${task.state.toLowerCase()}`}>{labels[task.state]}</span></p>
      <FormError error={error} />
      {mode === 'remove' ? (
        <div className="catalog-actions">
          <button type="button" className="catalog-button danger" disabled={busy}
            onClick={() => run(() => deleteTask(task.id), 'Task removed.')}>Confirm removal</button>
          <button type="button" className="catalog-button" disabled={busy} onClick={() => show(null)}>Cancel</button>
        </div>
      ) : mode ? (
        <form key={mode} className="catalog-actions" onSubmit={(event) => {
          event.preventDefault()
          const date = String(new FormData(event.currentTarget).get('date'))
          void run(() => moveTask(task.id, date, mode === 'carry'), mode === 'carry' ? 'Task carried forward.' : 'Task rescheduled.')
        }}>
          <label>New date<input type="date" name="date" required min={mode === 'carry' ? followingDay : '0001-01-01'} max="9999-12-31"
            defaultValue={mode === 'carry' ? followingDay : task.date} disabled={busy} /></label>
          <button type="submit" className="catalog-button primary" disabled={busy}>Save date</button>
          <button type="button" className="catalog-button" disabled={busy} onClick={() => show(null)}>Cancel</button>
        </form>
      ) : (
        <div className="catalog-actions">
          {!completed && (
            <>
              <button type="button" className="catalog-button" disabled={busy} onClick={() => show('move')}>Reschedule</button>
              <button type="button" className="catalog-button" disabled={busy || !followingDay} onClick={() => show('carry')}>Carry forward</button>
              <button type="button" className="catalog-button" disabled={busy}
                onClick={() => run(() => skipTask(task.id, !skipped), skipped ? 'Task restored.' : 'Task skipped.')}>{skipped ? 'Restore' : 'Skip'}</button>
            </>
          )}
          <button type="button" className="catalog-button danger" disabled={busy} onClick={() => show('remove')}>Remove</button>
        </div>
      )}
    </li>
  )
}
