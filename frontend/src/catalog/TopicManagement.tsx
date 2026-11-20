import { useState, type FormEvent } from 'react'
import { deleteTopic, listTopics, saveTopic, type Topic } from './api'
import { asError, FormError, LoadMessage } from './components'
import { useResource } from './useResource'

export function TopicManagement() {
  const { result, retry } = useResource(listTopics)
  const [editing, setEditing] = useState<Topic | null>(null)
  const [name, setName] = useState('')
  const [confirmation, setConfirmation] = useState<Topic | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const [message, setMessage] = useState('')

  function edit(topic: Topic | null) {
    setEditing(topic)
    setName(topic?.name ?? '')
    setConfirmation(null)
    setError(null)
    setMessage('')
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setConfirmation(null)
    setError(null)
    setMessage('')
    try {
      await saveTopic(name.trim(), editing?.id)
      setMessage(editing ? 'Topic renamed.' : 'Topic added.')
      setEditing(null)
      setName('')
      retry()
    } catch (failure) {
      setError(asError(failure))
    } finally {
      setBusy(false)
    }
  }

  async function remove(topic: Topic) {
    setBusy(true)
    setError(null)
    setMessage('')
    try {
      await deleteTopic(topic.id)
      setMessage('Topic deleted.')
      setConfirmation(null)
      retry()
    } catch (failure) {
      setError(asError(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <header className="catalog-heading"><div><h1>Topics</h1><p className="body-text">Add and manage the topics used to organize your library.</p></div></header>
      {message && <p className="catalog-success" role="status">{message}</p>}
      <FormError error={error} />
      <form className="catalog-panel topic-editor" onSubmit={submit}>
        <label>{editing ? 'Rename topic' : 'New topic'}
          <input name="name" required maxLength={80} value={name} onChange={(event) => setName(event.target.value)} placeholder="e.g. Dynamic Programming" disabled={busy} />
        </label>
        <div className="catalog-actions">
          <button type="submit" className="catalog-button primary" disabled={busy}>{editing ? 'Save name' : 'Add topic'}</button>
          {editing && <button type="button" className="catalog-button" onClick={() => edit(null)} disabled={busy}>Cancel edit</button>}
        </div>
      </form>
      {result.state !== 'ready' ? (
        <LoadMessage label="topics" retry={retry} error={result.state === 'error' ? result.error : undefined} />
      ) : (
        <section className="catalog-panel" aria-label="Saved topics">
          {result.data.length ? (
            <ul className="catalog-list">
              {result.data.map((topic) => (
                <li key={topic.id} className="topic-row">
                  <strong>{topic.name}</strong>
                  {confirmation?.id === topic.id ? (
                    <div className="catalog-actions" aria-label={`Confirm deletion of ${topic.name}`}>
                      <button type="button" className="catalog-button danger" onClick={() => remove(topic)} disabled={busy}>Confirm deletion</button>
                      <button type="button" className="catalog-button" onClick={() => { setConfirmation(null); setError(null) }} disabled={busy}>Cancel</button>
                    </div>
                  ) : (
                    <div className="catalog-actions">
                      <button type="button" className="catalog-button" onClick={() => edit(topic)} disabled={busy}
                        aria-label={`Rename ${topic.name}`}>Rename</button>
                      <button type="button" className="catalog-button danger" disabled={busy} aria-label={`Delete ${topic.name}`}
                        onClick={() => { edit(null); setConfirmation(topic) }}>Delete</button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          ) : <p className="body-text">No topics yet. Add one to organize your problems.</p>}
        </section>
      )}
    </>
  )
}
