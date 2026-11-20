export type HealthResponse = { status: 'UP' | 'DOWN' }

export async function getHealth(signal?: AbortSignal): Promise<HealthResponse> {
  const timeout = AbortSignal.timeout(5_000)
  const response = await fetch('/api/health', {
    headers: { Accept: 'application/json' },
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
  })

  if (response.status !== 200 && response.status !== 503) {
    throw new Error(`Health request returned HTTP ${response.status}`)
  }

  const body: unknown = await response.json()
  const expectedStatus = response.ok ? 'UP' : 'DOWN'
  if (typeof body !== 'object' || body === null || !('status' in body) || body.status !== expectedStatus) {
    throw new Error('Unexpected health response')
  }

  return { status: expectedStatus }
}
