import { useCallback, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { difficulties, getProblem, listTopics, saveProblem, type Difficulty, type Problem, type Topic } from './api'
import { asError, FormError, LoadMessage } from './components'
import { useResource } from './useResource'

export function ProblemForm() {
  const { id } = useParams()
  const load = useCallback(async (signal: AbortSignal) => {
    const [topics, problem] = await Promise.all([listTopics(signal), id ? getProblem(id, signal) : undefined])
    return { topics, problem }
  }, [id])
  const { result, retry } = useResource(load)
  const title = id ? 'Edit problem' : 'Add problem'
  return (
    <>
      <div className="catalog-back"><Link className="catalog-link" to={id ? `/problems/${id}` : '/problems'}>Back to {id ? 'problem' : 'problems'}</Link></div>
      <h1>{title}</h1>
      {result.state === 'ready'
        ? <Editor key={id ?? 'new'} topics={result.data.topics} problem={result.data.problem} />
        : <LoadMessage label={id ? 'problem' : 'topics'} retry={retry} error={result.state === 'error' ? result.error : undefined} />}
    </>
  )
}

function Editor({ topics, problem }: { topics: Topic[]; problem?: Problem }) {
  const navigate = useNavigate()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<Error | null>(null)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    const fields = new FormData(event.currentTarget)
    const topicIds = fields.getAll('topicIds').map(Number)
    const leetcodeId = Number(fields.get('leetcodeId'))
    if (!topicIds.length || !Number.isSafeInteger(leetcodeId) || leetcodeId <= 0) {
      setError(new Error(!topicIds.length ? 'Choose at least one topic.' : 'Enter a positive whole-number LeetCode ID.'))
      return
    }
    setError(null)
    setBusy(true)
    try {
      const saved = await saveProblem({
        leetcodeId,
        title: String(fields.get('title')).trim(),
        url: String(fields.get('url')).trim(),
        difficulty: String(fields.get('difficulty')) as Difficulty,
        topicIds,
      }, problem?.id)
      navigate(`/problems/${saved.id}`, { replace: true, state: { message: problem ? 'Problem updated.' : 'Problem added.' } })
    } catch (failure) {
      setError(asError(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="catalog-panel catalog-editor" onSubmit={submit}>
      <FormError error={error} />
      <fieldset disabled={busy}>
        <div className="catalog-form-grid">
          <label>LeetCode ID<input name="leetcodeId" type="number" min={1} step={1} required defaultValue={problem?.leetcodeId} /></label>
          <label>Difficulty<select name="difficulty" defaultValue={problem?.difficulty ?? ''} required>
            <option value="" disabled>Choose a difficulty</option>
            {Object.entries(difficulties).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select></label>
          <label className="catalog-wide">Title<input name="title" required maxLength={255} defaultValue={problem?.title} /></label>
          <label className="catalog-wide">LeetCode URL<input name="url" type="url" required maxLength={2048}
            placeholder="https://leetcode.com/problems/two-sum/" defaultValue={problem?.url} /></label>
        </div>
        <fieldset className="topic-picker">
          <legend>Topics <span className="body-text">(select at least one)</span></legend>
          {topics.length ? (
            <div className="topic-choices">
              {topics.map((topic) => (
                <label key={topic.id} className="topic-option">
                  <input type="checkbox" name="topicIds" value={topic.id}
                    defaultChecked={problem?.topics.some((existing) => existing.id === topic.id)} />
                  {topic.name}
                </label>
              ))}
            </div>
          ) : <p className="body-text">Create a topic in <Link to="/topics" className="catalog-link">Topic Management</Link> to add a problem.</p>}
        </fieldset>
        <div className="catalog-actions">
          <button className="catalog-button primary" disabled={!topics.length} type="submit">{busy ? 'Saving…' : 'Save problem'}</button>
          <Link className="catalog-button" to={problem ? `/problems/${problem.id}` : '/problems'}>Cancel</Link>
        </div>
      </fieldset>
    </form>
  )
}
