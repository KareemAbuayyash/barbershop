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
})
