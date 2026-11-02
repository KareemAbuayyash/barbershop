export interface GuestBookingLink {
  bookingId: string
  token: string
}

const LINKS_STORAGE = 'barber-house-guest-booking-links'
const LATEST_TOKEN_STORAGE = 'barber-house-guest-notification-token'

export function readGuestBookingLinks(): GuestBookingLink[] {
  let links: GuestBookingLink[] = []
  try {
    const saved = window.localStorage.getItem(LINKS_STORAGE)
    const parsed: unknown = saved ? JSON.parse(saved) : []
    if (Array.isArray(parsed)) {
      links = parsed.filter((item): item is GuestBookingLink =>
        typeof item?.bookingId === 'string' && typeof item?.token === 'string')
    }
  } catch {
    links = []
  }

  const legacyToken = window.localStorage.getItem(LATEST_TOKEN_STORAGE)
  if (legacyToken && !links.some((link) => link.token === legacyToken)) {
    links.push({ bookingId: '', token: legacyToken })
  }
  return links
}

export function rememberGuestBookingLink(bookingId: string, token: string) {
  const links = readGuestBookingLinks().filter((link) => link.token !== token && link.bookingId !== bookingId)
  links.push({ bookingId, token })
  window.localStorage.setItem(LINKS_STORAGE, JSON.stringify(links.slice(-30)))
  window.localStorage.setItem(LATEST_TOKEN_STORAGE, token)
  window.dispatchEvent(new Event('barbershop:guest-bookings-updated'))
}

export function findGuestBookingToken(bookingId: string) {
  return readGuestBookingLinks().find((link) => link.bookingId === bookingId)?.token ?? ''
}

export function latestGuestBookingToken() {
  return readGuestBookingLinks().at(-1)?.token ?? ''
}
