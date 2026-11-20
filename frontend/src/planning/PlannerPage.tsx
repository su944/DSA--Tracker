import { useCallback, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { LoadMessage } from '../catalog/components'
import { useResource } from '../catalog/useResource'
import { getCalendar, type Counts, type PlannerView } from './api'
import { dateRange, displayDate, displayWeekday, shiftDate } from './dates'
import { ProblemPicker } from './ProblemPicker'
import { PlannerTask } from './PlannerTask'

export function PlannerPage() {
  const [params, setParams] = useSearchParams()
  const date = params.get('date') ?? ''
  const view: PlannerView = params.get('view') === 'WEEK' ? 'WEEK' : 'DAY'
  const load = useCallback((signal: AbortSignal) => getCalendar(view, date, signal), [view, date])
  const calendar = useResource(load)
  const [pickerDate, setPickerDate] = useState<string | null>(null)
  const [message, setMessage] = useState('')

  function select(nextView: PlannerView, nextDate: string) {
    setPickerDate(null)
    setMessage('')
    const next = new URLSearchParams({ view: nextView })
    if (nextDate) next.set('date', nextDate)
    setParams(next)
  }

  return (
    <>
      <header className="catalog-heading">
        <div><h1>Daily Planner</h1><p className="body-text">Schedule practice and see what you completed.</p></div>
        <div className="catalog-actions" aria-label="Calendar view">
          <button type="button" className={`catalog-button ${view === 'DAY' ? 'primary' : ''}`}
            aria-pressed={view === 'DAY'} onClick={() => select('DAY', date)}>Daily view</button>
          <button type="button" className={`catalog-button ${view === 'WEEK' ? 'primary' : ''}`}
            aria-pressed={view === 'WEEK'} onClick={() => select('WEEK', date)}>Weekly view</button>
        </div>
      </header>
      {message && <p className="catalog-success" role="status">{message}</p>}
      {calendar.result.state !== 'ready' ? (
        <>
          {calendar.result.state === 'error' && <button type="button" className="catalog-button" onClick={() => select(view, '')}>Go to today</button>}
          <LoadMessage label="calendar" retry={calendar.retry} error={calendar.result.state === 'error' ? calendar.result.error : undefined} />
        </>
      ) : (
        <CalendarContent calendar={calendar.result.data} pickerDate={pickerDate} onDate={select} onPick={setPickerDate}
          onChanged={(change) => {
            setMessage(change)
            setPickerDate(null)
            calendar.retry()
          }} />
      )}
    </>
  )
}

function CountsLabel({ counts }: { counts: Counts }) {
  return (
    <p className="planning-counts">
      <span><strong>{counts.planned}</strong> planned</span>
      <span><strong>{counts.completed}</strong> completed</span>
      {counts.skipped > 0 && <span><strong>{counts.skipped}</strong> skipped</span>}
    </p>
  )
}

type CalendarContentProps = {
  calendar: Awaited<ReturnType<typeof getCalendar>>
  pickerDate: string | null
  onDate: (view: PlannerView, date: string) => void
  onPick: (date: string | null) => void
  onChanged: (message: string) => void
}

function CalendarContent({ calendar, pickerDate, onDate, onPick, onChanged }: CalendarContentProps) {
  const { view, date } = calendar
  const step = view === 'WEEK' ? 7 : 1
  const previous = shiftDate(date, -step)
  const next = shiftDate(date, step)
  return (
    <>
      <section className="catalog-panel planning-toolbar" aria-label="Calendar controls">
        <div>
          <h2>{dateRange(calendar.startDate, calendar.endDate)}</h2>
          <CountsLabel counts={calendar.counts} />
        </div>
        <div className="catalog-actions">
          <button type="button" className="catalog-button" disabled={!previous} onClick={() => onDate(view, previous)}>Previous {view === 'WEEK' ? 'week' : 'day'}</button>
          <button type="button" className="catalog-button" onClick={() => onDate(view, calendar.today)}>Today</button>
          <button type="button" className="catalog-button" disabled={!next} onClick={() => onDate(view, next)}>Next {view === 'WEEK' ? 'week' : 'day'}</button>
          <label>Calendar date<input type="date" min="0001-01-01" max="9999-12-31" value={date} onChange={(event) => {
            if (event.currentTarget.validity.valid && event.target.value) onDate(view, event.target.value)
          }} /></label>
        </div>
      </section>
      {pickerDate && <ProblemPicker key={pickerDate} date={pickerDate}
        scheduledIds={calendar.days.find((day) => day.date === pickerDate)?.items.map((item) => item.problem.id) ?? []}
        onClose={() => onPick(null)} onSaved={() => onChanged('Problem scheduled.')} />}
      <div className={view === 'WEEK' ? 'planning-week' : 'planning-day'}>
        {calendar.days.map((day) => (
          <section key={day.date} className="catalog-panel planning-date" aria-labelledby={`day-${day.date}`}>
            <header>
              <h3 id={`day-${day.date}`}>{displayWeekday(day.date)}</h3>
              <p className="body-text"><time dateTime={day.date}>{displayDate(day.date)}</time>{day.date === calendar.today ? ' · Today' : ''}</p>
              {view === 'WEEK' && <CountsLabel counts={day.counts} />}
              <button type="button" className="catalog-button" onClick={() => onPick(day.date)}
                aria-label={`Add problem for ${displayDate(day.date)}`}>Add problem</button>
            </header>
            {day.items.length ? (
              <ul className="catalog-list">{day.items.map((item) => <PlannerTask key={item.id} task={item} onChanged={onChanged} />)}</ul>
            ) : <p className="body-text planning-empty-day">No scheduled problems.</p>}
          </section>
        ))}
      </div>
    </>
  )
}
