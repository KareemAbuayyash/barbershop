const shopTimeZone = 'Asia/Hebron'

export function getShopDate(): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: shopTimeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date())
  const values = Object.fromEntries(parts.map(({ type, value }) => [type, value]))
  return `${values.year}-${values.month}-${values.day}`
}

export function shopLocalDateTimeToIso(value: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value)
  if (!match) {
    throw new Error('Choose a valid date and time.')
  }

  const [, year, month, day, hour, minute] = match
  const wallClock = Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute))
  let instant = wallClock
  const formatter = new Intl.DateTimeFormat('en-GB', {
    timeZone: shopTimeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  })

  for (let attempt = 0; attempt < 3; attempt += 1) {
    const parts = Object.fromEntries(
      formatter.formatToParts(new Date(instant)).map(({ type, value: part }) => [type, part]),
    )
    const shopWallClock = Date.UTC(
      Number(parts.year),
      Number(parts.month) - 1,
      Number(parts.day),
      Number(parts.hour),
      Number(parts.minute),
    )
    instant += wallClock - shopWallClock
  }

  const result = new Date(instant)
  const check = Object.fromEntries(
    formatter.formatToParts(result).map(({ type, value: part }) => [type, part]),
  )
  if (
    check.year !== year
    || check.month !== month
    || check.day !== day
    || check.hour !== hour
    || check.minute !== minute
  ) {
    throw new Error('That local time does not exist in the shop time zone.')
  }

  return result.toISOString()
}

export function formatShopDateTime(value: string | null): string {
  if (!value) {
    return 'لم يُحدد بعد'
  }
  return new Intl.DateTimeFormat('ar', {
    timeZone: shopTimeZone,
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}
