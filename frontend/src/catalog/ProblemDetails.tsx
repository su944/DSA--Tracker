import { useCallback, useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { deleteProblem, difficulties, getProblem, saveProgress, statuses, type Problem, type Progress, type ProgressPatch, type ProgressStatus } from './api'
import { asError, FormError, LoadMessage } from './components'
import { useResource } from './useResource'

export function ProblemDetails() {
  const { id = '' } = useParams()
  const load = useCallback((signal: AbortSignal) => getProblem(id, signal), [id])
  const { result, retry } = useResource(load)
  const location = useLocation()
  return (
    <>
      <div className="catalog-back"><Link to="/problems" className="catalog-link">Back to problems</Link></div>
      {result.state === 'ready' ? (
        <Details key={result.data.id} problem={result.data} message={
          typeof location.state?.message === 'string' ? location.state.message : undefined
        } />
      ) : (
        <><h1>Problem details</h1><LoadMessage label="problem" retry={retry} error={result.state === 'error' ? result.error : undefined} /></>
      )}
    </>
  )
}

function Details({ problem, message }: { problem: Problem; message?: string }) {
  const navigate = useNavigate()
  const [progress, setProgress] = useState(problem.progress)
  const [confirmation, setConfirmation] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState<Error | null>(null)

  async function remove() {
    setDeleteError(null)
    setDeleting(true)
    try {
      await deleteProblem(problem.id)
      navigate('/problems', { replace: true, state: { message: 'Problem deleted.' } })
    } catch (error) {
      setDeleteError(asError(error))
      setDeleting(false)
    }
  }

  return (
    <>
      <header className="catalog-heading">
        <div>
          <p className="body-text">LeetCode #{problem.leetcodeId}</p>
          <h1>{problem.title}</h1>
          <div className="catalog-meta">
            <span className={`catalog-badge difficulty-${problem.difficulty.toLowerCase()}`}>{difficulties[problem.difficulty]}</span>
            <a href={problem.url} target="_blank" rel="noreferrer" className="catalog-link">Open on LeetCode</a>
          </div>
          <div className="topic-tags">{problem.topics.map((topic) => <span key={topic.id}>{topic.name}</span>)}</div>
        </div>
        <div className="catalog-actions">
          <Link to={`/problems/${problem.id}/edit`} className="catalog-button">Edit problem</Link>
          <button type="button" className="catalog-button danger" onClick={() => setConfirmation(true)} disabled={confirmation}>Delete problem</button>
        </div>
      </header>
      {message && <p className="catalog-success" role="status">{message}</p>}
      {confirmation && (
        <section className="catalog-panel catalog-confirmation" aria-label="Confirm problem deletion">
          <p>Delete this problem and its saved progress?</p>
          <FormError error={deleteError} />
          <div className="catalog-actions">
            <button type="button" className="catalog-button danger" onClick={remove} disabled={deleting}>{deleting ? 'Deleting…' : 'Confirm deletion'}</button>
            <button type="button" className="catalog-button" onClick={() => setConfirmation(false)} disabled={deleting}>Keep problem</button>
          </div>
        </section>
      )}
      <ProgressEditor progress={progress} onSave={async (patch) => setProgress(await saveProgress(problem.id, patch))} />
    </>
  )
}

export function progressPatch(status: ProgressStatus, attempted: string, solved: string, comments: string): ProgressPatch {
  const patch: ProgressPatch = { status, comments }
  if (status === 'SOLVED') {
    patch.attemptedDate = attempted || null
    if (solved) patch.solvedDate = solved
  } else if (status === 'ATTEMPTED' && attempted) {
    patch.attemptedDate = attempted
  }
  return patch
}

function ProgressEditor({ progress, onSave }: { progress: Progress; onSave: (patch: ProgressPatch) => Promise<void> }) {
  const [status, setStatus] = useState(progress.status)
  const [formId, setFormId] = useState(0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const [saved, setSaved] = useState(false)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    const values = new FormData(event.currentTarget)
    setError(null)
    setSaved(false)
    setBusy(true)
    try {
      await onSave(progressPatch(status, String(values.get('attemptedDate') ?? ''),
        String(values.get('solvedDate') ?? ''), String(values.get('comments') ?? '')))
      setSaved(true)
      setFormId((current) => current + 1)
    } catch (failure) {
      setError(asError(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="catalog-panel catalog-editor" aria-labelledby="progress-heading">
      <h2 id="progress-heading">Progress</h2>
      <p className="body-text">Current status: {statuses[progress.status]}</p>
      {saved && <p className="catalog-success" role="status">Progress saved.</p>}
      <FormError error={error} />
      <form key={formId} onSubmit={submit}>
        <fieldset disabled={busy}>
          <div className="catalog-form-grid">
            <label className="catalog-wide">Status<select name="status" value={status} onChange={(event) => {
              setStatus(event.target.value as ProgressStatus)
              setSaved(false)
            }}>
              {Object.entries(statuses).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select></label>
            <label>Attempted date<input name="attemptedDate" type="date" defaultValue={progress.attemptedDate ?? ''}
              disabled={status === 'NOT_STARTED'} required={status === 'ATTEMPTED' && Boolean(progress.attemptedDate)} /></label>
            <label>Solved date<input name="solvedDate" type="date" defaultValue={progress.solvedDate ?? ''}
              disabled={status !== 'SOLVED'} required={status === 'SOLVED' && Boolean(progress.solvedDate)} /></label>
            <p className="catalog-wide body-text">A newly required date defaults to today when left blank. Setting Not Started clears both dates.</p>
            <label className="catalog-wide">Comments<textarea name="comments" maxLength={10000} rows={5}
              defaultValue={progress.comments} placeholder="Notes from your practice" /></label>
          </div>
          <div className="catalog-actions"><button type="submit" className="catalog-button primary">{busy ? 'Saving…' : 'Save progress'}</button></div>
        </fieldset>
      </form>
    </section>
  )
}
