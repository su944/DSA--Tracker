import { useEffect, useState } from 'react'
import { getHealth } from './api'

export type BackendHealth = 'checking' | 'online' | 'unavailable' | 'offline'

export function useBackendHealth() {
  const [health, setHealth] = useState<BackendHealth>('checking')
  const [check, setCheck] = useState(0)

  useEffect(() => {
    const request = new AbortController()

    getHealth(request.signal).then(
      ({ status }) => {
        if (!request.signal.aborted) {
          setHealth(status === 'UP' ? 'online' : 'unavailable')
        }
      },
      () => {
        if (!request.signal.aborted) {
          setHealth('offline')
        }
      },
    )

    return () => request.abort()
  }, [check])

  function refresh() {
    setHealth('checking')
    setCheck((previous) => previous + 1)
  }

  return { health, refresh }
}
