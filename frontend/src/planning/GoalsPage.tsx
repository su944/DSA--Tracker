import { useState, type FormEvent } from 'react'
import { difficulties, listTopics, type Topic } from '../catalog/api'
import { asError, FormError, LoadMessage } from '../catalog/components'
import { useResource } from '../catalog/useResource'
import { deleteGoal, goalTypes, listGoals, saveGoal, type Goal, type GoalInput, type GoalType } from './api'
import { dateRange } from './dates'
import { GoalProgressMeter } from './GoalProgressMeter'
import { GoalProblems } from './GoalProblems'

export function GoalsPage() {
  const goals = useResource(listGoals)
  const [editor, setEditor] = useState<Goal | 'new' | null>(null)
  const [confirmation, setConfirmation] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const [message, setMessage] = useState('')

  function openEditor(goal: Goal | 'new') {
    setEditor(goal)
    setConfirmation(null)
    setError(null)
    setMessage('')
  }

  async function remove(goal: Goal) {
    setError(null)
    setMessage('')
    setBusy(true)
    try {
      await deleteGoal(goal.id)
      setMessage('Goal deleted.')
      setConfirmation(null)
      goals.retry()
    } catch (failure) {
      setError(asError(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <header className="catalog-heading">
        <div><h1>Goals</h1><p className="body-text">Set targets for problems solved in a period.</p></div>
        <button type="button" className="catalog-button primary" disabled={editor !== null || busy} onClick={() => openEditor('new')}>Add goal</button>
      </header>
      {message && <p className="catalog-success" role="status">{message}</p>}
      <FormError error={error} />
      {editor && <GoalEditor key={editor === 'new' ? 'new' : editor.id} goal={editor === 'new' ? undefined : editor}
        onCancel={() => setEditor(null)} onSaved={() => {
          setMessage(editor === 'new' ? 'Goal added.' : 'Goal updated.')
          setEditor(null)
          goals.retry()
        }} />}
      {goals.result.state !== 'ready' ? (
        <LoadMessage label="goals" retry={goals.retry} error={goals.result.state === 'error' ? goals.result.error : undefined} />
      ) : goals.result.data.length === 0 ? (
        <p className="catalog-panel body-text">No goals yet. Add a goal to track your solved problems.</p>
      ) : (
        <ul className="planning-goal-list" aria-label="Saved goals">
          {goals.result.data.map((goal) => (
            <li key={goal.id} className="catalog-panel planning-goal">
              <div>
                <p className="planning-meta">{goalTypes[goal.type]} · {goal.topic?.name ?? 'All topics'} · {goal.difficulty ? difficulties[goal.difficulty] : 'All difficulties'}</p>
                <h2>{goal.name}</h2>
                <p className="body-text">{dateRange(goal.startDate, goal.endDate)}</p>
                <GoalProgressMeter goal={goal} />
              </div>
              {confirmation === goal.id ? (
                <div>
                  <p>Delete this goal?</p>
                  <div className="catalog-actions">
                    <button type="button" className="catalog-button danger" disabled={busy} onClick={() => remove(goal)}>Confirm deletion</button>
                    <button type="button" className="catalog-button" disabled={busy} onClick={() => {
                      setConfirmation(null)
                      setError(null)
                    }}>Keep goal</button>
                  </div>
                </div>
              ) : (
                <div className="catalog-actions">
                  <button type="button" className="catalog-button" disabled={editor !== null || busy}
                    aria-label={`Edit ${goal.name}`} onClick={() => openEditor(goal)}>Edit</button>
                  <button type="button" className="catalog-button danger" disabled={editor !== null || busy}
                    aria-label={`Delete ${goal.name}`} onClick={() => {
                      setConfirmation(goal.id)
                      setMessage('')
                      setError(null)
                    }}>Delete</button>
                </div>
              )}
              <GoalProblems goal={goal} />
            </li>
          ))}
        </ul>
      )}
    </>
  )
}

function GoalEditor({ goal, onSaved, onCancel }: { goal?: Goal; onSaved: () => void; onCancel: () => void }) {
  const topics = useResource(listTopics)
  return (
    <section className="catalog-panel catalog-editor" aria-labelledby="goal-editor-heading">
      <h2 id="goal-editor-heading">{goal ? 'Edit goal' : 'New goal'}</h2>
      {topics.result.state === 'ready' ? (
        <GoalForm goal={goal} topics={topics.result.data} onSaved={onSaved} onCancel={onCancel} />
      ) : (
        <>
          <LoadMessage label="topics" retry={topics.retry} error={topics.result.state === 'error' ? topics.result.error : undefined} />
          <button type="button" className="catalog-button" onClick={onCancel}>Cancel</button>
        </>
      )}
    </section>
  )
}

function GoalForm({ goal, topics, onSaved, onCancel }: { goal?: Goal; topics: Topic[]; onSaved: () => void; onCancel: () => void }) {
  const [type, setType] = useState<GoalType>(goal?.type ?? 'DAILY')
  const [error, setError] = useState<Error | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    const data = new FormData(event.currentTarget)
    const input: GoalInput = {
      type,
      name: String(data.get('name')).trim(),
      startDate: String(data.get('startDate')),
      endDate: type === 'CUSTOM' ? String(data.get('endDate')) : undefined,
      targetCount: Number(data.get('targetCount')),
      topicId: Number(data.get('topicId')) || null,
    }
    setError(null)
    setBusy(true)
    try {
      await saveGoal(input, goal?.id)
      onSaved()
    } catch (failure) {
      setError(asError(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit}>
      <FormError error={error} />
      <fieldset disabled={busy}>
        <div className="catalog-form-grid">
          <label className="catalog-wide">Goal name<input name="name" required maxLength={100} defaultValue={goal?.name} /></label>
          <label>Period<select name="type" value={type} onChange={(event) => setType(event.target.value as GoalType)}>
            {Object.entries(goalTypes).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select></label>
          <label>Target count<input name="targetCount" type="number" min={1} max={2147483647} step={1}
            required defaultValue={goal?.progress.targetCount ?? 1} /></label>
          <label>{type === 'DAILY' ? 'Date' : type === 'WEEKLY' ? 'Week starts on Monday' : 'Start date'}
            <input name="startDate" type="date" required min="0001-01-01" max="9999-12-31" defaultValue={goal?.startDate} />
          </label>
          {type === 'CUSTOM' && <label>End date<input name="endDate" type="date" required min="0001-01-01" max="9999-12-31"
            defaultValue={goal?.endDate} /></label>}
          <label className="catalog-wide">Topic<select name="topicId" defaultValue={goal?.topic?.id ?? ''}>
            <option value="">All topics</option>
            {topics.map((topic) => <option key={topic.id} value={topic.id}>{topic.name}</option>)}
          </select></label>
        </div>
        <div className="catalog-actions">
          <button type="submit" className="catalog-button primary">{busy ? 'Saving…' : 'Save goal'}</button>
          <button type="button" className="catalog-button" onClick={onCancel}>Cancel</button>
        </div>
      </fieldset>
    </form>
  )
}
