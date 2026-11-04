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
  serviceId: string
  bookingMode: BookingMode | 'LEGACY'
  status: BookingStatus
  startsAt: string | null
  queueDate: string | null
  queuePosition: number | null
  notes: string | null
  ownerNote: string | null
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
  imageUrl: string | null
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
  preferredDate: string | null
  queueDate: string | null
  queuePosition: number | null
  notes: string | null
  ownerNote: string | null
  serviceId: string
  serviceName: string
  servicePrice: number
  serviceDurationMinutes: number
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
  guestAccessToken?: string | null
}

let csrfToken: string | undefined
let csrfHeaderName = 'X-CSRF-TOKEN'

async function readError(response: Response): Promise<ApiError> {
  let message = 'تعذر إكمال الطلب. حاول مرة أخرى.'
  const contentType = response.headers.get('content-type') ?? ''

  if (contentType.includes('json')) {
    const body = await response.json().catch(() => undefined) as ApiErrorBody | undefined
    message = body?.detail || body?.title || message
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
    if (init.body instanceof FormData) {
      headers.delete('Content-Type')
    } else {
      headers.set('Content-Type', 'application/json')
    }
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

  const sendRequest = () => fetch(path, {
    ...init,
    method,
    headers,
    credentials: 'include',
  })
  let response = await sendRequest()

  // Retry only when the server identifies CSRF as the cause. A role or other
  // authorization failure must not replay a state-changing request.
  if (response.status === 403 && !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)) {
    const deniedBody = await response.clone().json().catch(() => undefined) as ApiErrorBody | undefined
    const csrfWasRejected = `${deniedBody?.detail ?? ''} ${deniedBody?.title ?? ''}`
      .toLowerCase()
      .includes('csrf')
    if (csrfWasRejected) {
      csrfToken = undefined
      try {
        await getCsrfToken()
        if (csrfToken) {
          headers.set(csrfHeaderName, csrfToken)
          response = await sendRequest()
        }
      } catch {
        // Preserve the original response if the token refresh fails.
      }
    }
  }

  if (!response.ok) {
    const checkingExpiredProtectedSession = path === '/api/auth/me'
      && (window.location.pathname.startsWith('/owner')
        || window.location.pathname.startsWith('/my-bookings'))
    if (response.status === 401
      && path !== '/api/auth/login'
      && path !== '/api/auth/register'
      && (path !== '/api/auth/me' || checkingExpiredProtectedSession)) {
      window.dispatchEvent(new Event('barbershop:session-expired'))
    }
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
      if (error.message.toLowerCase().includes('csrf')) {
        return 'انتهت صلاحية رمز الحماية. حدّث الصفحة ثم أعد تسجيل الدخول.'
      }
      return 'ليست لديك صلاحية لإجراء هذه العملية.'
    }
    if (error.status === 400 || error.status === 422) {
      return 'البيانات المدخلة غير مكتملة أو غير صحيحة. راجع الحقول وحاول مجدداً.'
    }
    if (error.status === 404) return 'لم نعثر على البيانات المطلوبة. حدّث الصفحة وحاول مرة أخرى.'
    if (error.status === 409) {
      return 'لا يمكن إتمام العملية بهذه البيانات حالياً. حدّث الصفحة وتحقق من حالة الحساب أو الرصيد.'
    }
    if (error.status === 413) return 'حجم الملف أكبر من المسموح. اختر صورة أصغر ثم حاول مجدداً.'
    if (error.status === 415) return 'نوع الملف غير مدعوم. اختر ملفاً بصيغة أخرى.'
    if (error.status === 429) return 'وصلت إلى حد المحاولات المسموح. انتظر قليلاً ثم حاول مجدداً.'
    if (error.status >= 500) return 'حدث خلل مؤقت في الخادم. حاول مرة أخرى بعد قليل.'
    return 'تعذر إكمال الطلب. تحقق من البيانات وحاول مرة أخرى.'
  }
  return fallback || 'تعذر إكمال الطلب. حاول مرة أخرى.'
}
