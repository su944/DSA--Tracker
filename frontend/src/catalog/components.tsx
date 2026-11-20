import { CatalogError } from './api'

export function LoadMessage({ error, retry, label }: { error?: string; retry: () => void; label: string }) {
  if (!error) return <p className="catalog-message" role="status">{'Loading ' + label + '…'}</p>
  return (
    <div className="catalog-message">
      <p role="alert">{error}</p>
      <button type="button" className="catalog-button" onClick={retry}>Try again</button>
    </div>
  )
}

export function FormError({ error }: { error: Error | null }) {
  if (!error) return null
  const fields = error instanceof CatalogError ? error.fields : {}
  return (
    <div className="catalog-error" role="alert">
      <p>{error.message}</p>
      {Object.keys(fields).length > 0 && !Object.values(fields).includes(error.message) && (
        <ul>{Object.entries(fields).map(([field, message]) => <li key={field}>{fieldLabel(field)}: {message}</li>)}</ul>
      )}
    </div>
  )
}

function fieldLabel(field: string) {
  return ({
    leetcodeId: 'LeetCode ID', title: 'Title', url: 'LeetCode URL', difficulty: 'Difficulty',
    topicIds: 'Topics', name: 'Topic name', attemptedDate: 'Attempted date', solvedDate: 'Solved date',
    comments: 'Comments', status: 'Status',
  } as Record<string, string>)[field] ?? field
}

export function asError(error: unknown) {
  return error instanceof Error ? error : new Error('The request failed. Try again.')
}
