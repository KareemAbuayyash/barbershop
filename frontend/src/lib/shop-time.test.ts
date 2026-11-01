import { describe, expect, it } from 'vitest'
import { shopLocalDateTimeToIso } from './shop-time'

describe('shop-local booking times', () => {
  it('converts winter shop time to an ISO instant', () => {
    expect(shopLocalDateTimeToIso('2025-01-15T12:30')).toBe('2025-01-15T10:30:00.000Z')
  })

  it('converts summer shop time using the daylight-saving offset', () => {
    expect(shopLocalDateTimeToIso('2025-07-15T12:30')).toBe('2025-07-15T09:30:00.000Z')
  })

  it('rejects values that are not a local date and time', () => {
    expect(() => shopLocalDateTimeToIso('not-a-date')).toThrow('Choose a valid date and time.')
  })
})
