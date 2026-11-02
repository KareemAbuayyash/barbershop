export interface ApiErrorBody {
  title?: string
  detail?: string
}

export class ApiError extends Error {
  status: number
  retryAfter?: string

  constructor(message: string, status: number, retryAfter?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.retryAfter = retryAfter
  }
}

export interface CurrentUser {
  username: string
  authorities: string[]
}

export interface CustomerBooking {
  id: string
  bookingMode: BookingMode | 'LEGACY'
  status: BookingStatus
  startsAt: string | null
  queueDate: string | null
  queuePosition: number | null
  serviceName: string
  servicePrice: number
  serviceDurationMinutes: number
  createdAt: string
}

export interface Service {
  id: string
  name: string
  description: string
  price: number
  durationMinutes: number
  active: boolean
  createdAt: string
}

export type BookingMode =
  | 'EXACT_APPOINTMENT'
  | 'CONFIRMATION_REQUEST'
  | 'DAY_QUEUE'
  | 'SAME_DAY_QUEUE'

export type BookingStatus = 'PENDING' | 'CONFIRMED' | 'COMPLETED' | 'CANCELLED'

export interface BookingSettings {
  exactAppointmentEnabled: boolean
  confirmationRequestEnabled: boolean
  dayQueueEnabled: boolean
  sameDayQueueEnabled: boolean
  newBookingsDisabled: boolean
  updatedAt: string
}

export interface Booking {
  id: string
  bookingMode: BookingMode | 'LEGACY'
  status: BookingStatus
  startsAt: string | null
  queueDate: string | null
  queuePosition: number | null
  notes: string | null
  serviceId: string
  serviceName: string
  servicePrice: number
  serviceDurationMinutes: number
  employeeId?: string | null
  customerName: string
  customerPhone: string | null
  createdAt: string
  updatedAt: string
}

export interface BookingPage {
  content: Booking[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface GuestBookingResult {
  bookingId: string
  bookingMode: BookingMode
  status: BookingStatus
  startsAt: string | null
  queueDate: string | null
  queuePosition: number | null
  createdAt: string
}

let csrfToken: string | undefined
let csrfHeaderName = 'X-CSRF-TOKEN'

async function readError(response: Response): Promise<ApiError> {
  let message = `Request failed (${response.status})`
  const contentType = response.headers.get('content-type') ?? ''

  if (contentType.includes('json')) {
    const body = await response.json() as ApiErrorBody
    message = body.detail || body.title || message
  }

  return new ApiError(message, response.status, response.headers.get('Retry-After') ?? undefined)
}

export async function getCsrfToken(): Promise<void> {
  const response = await fetch('/api/csrf', {
    credentials: 'include',
    headers: { Accept: 'application/json' },
  })

  if (!response.ok) {
    throw await readError(response)
  }

  const token = await response.json() as {
    headerName: string
    token: string
  }
  csrfHeaderName = token.headerName
  csrfToken = token.token
}

export async function apiRequest<T>(
  path: string,
  init: RequestInit = {},
): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')

  if (init.body !== undefined) {
    headers.set('Content-Type', 'application/json')
  }

  if (!['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)) {
    if (!csrfToken) {
      await getCsrfToken()
    }
    const token = csrfToken
    if (!token) {
      throw new Error('Unable to obtain a CSRF token.')
    }
    headers.set(csrfHeaderName, token)
  }

  const response = await fetch(path, {
    ...init,
    method,
    headers,
    credentials: 'include',
  })

  if (!response.ok) {
    throw await readError(response)
  }

  if (response.status === 204) {
    return undefined as T
  }

  return await response.json() as T
}

export function clearCsrfToken(): void {
  csrfToken = undefined
}

export function getErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.status === 401) {
      return 'انتهت جلسة الدخول أو لم يتم تسجيل الدخول. سجّل الدخول مجدداً.'
    }
    if (error.status === 403) {
      return 'هذه العملية تحتاج صلاحية المالك. تحقّق من الحساب المسجّل ثم أعد تسجيل الدخول.'
    }
    return error.message
  }
  return fallback
}
