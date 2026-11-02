import { afterEach, describe, expect, it, vi } from 'vitest'

describe('session API requests', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.resetModules()
  })

  it('sends cookies and the fetched CSRF token with state-changing requests', async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'test-csrf-token',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ bookingId: 'booking-1' }), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      }))
    vi.stubGlobal('fetch', fetchMock)

    const { apiRequest, getCsrfToken } = await import('./api')
    await getCsrfToken()
    await apiRequest('/api/bookings', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'request-1' },
      body: JSON.stringify({ bookingMode: 'SAME_DAY_QUEUE' }),
    })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    const requestOptions = fetchMock.mock.calls[1]?.[1]
    expect(requestOptions?.credentials).toBe('include')
    const headers = new Headers(requestOptions?.headers)
    expect(headers.get('X-CSRF-TOKEN')).toBe('test-csrf-token')
    expect(headers.get('Idempotency-Key')).toBe('request-1')
    expect(headers.get('Content-Type')).toBe('application/json')
  })

  it('reads Spring problem details from JSON API errors', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({
        title: 'Invalid request',
        status: 400,
        detail: 'The date range is invalid.',
      }), {
        status: 400,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    ))

    const { apiRequest, getErrorMessage } = await import('./api')
    const error = await apiRequest('/api/bookings').catch((cause: unknown) => cause)

    expect(getErrorMessage(error, 'fallback')).toBe('The date range is invalid.')
  })

  it('does not replay a state-changing request when a 403 is not a CSRF rejection', async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
        token: 'test-csrf-token',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        title: 'Forbidden', status: 403, detail: 'Access is denied.',
      }), { status: 403, headers: { 'Content-Type': 'application/problem+json' } }))
    vi.stubGlobal('fetch', fetchMock)

    const { apiRequest } = await import('./api')
    await expect(apiRequest('/api/notifications/subscriptions', {
      method: 'POST', body: JSON.stringify({ endpoint: 'https://push.example.test', keys: {} }),
    })).rejects.toMatchObject({ status: 403 })

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('refreshes the CSRF token and retries only an explicit CSRF rejection', async () => {
    const sentTokens: string[] = []
    const responses = [
      new Response(JSON.stringify({
        headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'old-token',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
      new Response(JSON.stringify({
        title: 'Forbidden', status: 403, detail: 'CSRF token is missing or invalid.',
      }), { status: 403, headers: { 'Content-Type': 'application/problem+json' } }),
      new Response(JSON.stringify({
        headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'fresh-token',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
      new Response(null, { status: 204 }),
    ]
    const fetchMock = vi.fn<typeof fetch>(async (_input, init) => {
      sentTokens.push(new Headers(init?.headers).get('X-CSRF-TOKEN') ?? '')
      return responses.shift()!
    })
    vi.stubGlobal('fetch', fetchMock)

    const { apiRequest } = await import('./api')
    await apiRequest('/api/notifications/subscriptions', { method: 'POST', body: '{}' })

    expect(fetchMock).toHaveBeenCalledTimes(4)
    expect(sentTokens[1]).toBe('old-token')
    expect(sentTokens[3]).toBe('fresh-token')
  })
})
