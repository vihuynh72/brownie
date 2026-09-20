import { describe, expect, it } from 'vitest'
import { dayCount, hourCount } from '@/periods'

describe('periods', () => {
  it('reads as a person would say it, singular included', () => {
    expect(dayCount(1)).toBe('a day')
    expect(dayCount(30)).toBe('30 days')
    expect(hourCount(1)).toBe('an hour')
    expect(hourCount(6)).toBe('6 hours')
    expect(hourCount(24)).toBe('a day')
    expect(hourCount(48)).toBe('2 days')
    expect(hourCount(36)).toBe('36 hours')
  })

  /** What an older server sends is no field at all; a sentence must be able to leave the clause out. */
  it('answers nothing for a value that is missing, zero, fractional or not a number', () => {
    for (const value of [undefined, null, 0, -3, 1.5, Number.NaN, '30', {}]) {
      expect(dayCount(value)).toBeNull()
      expect(hourCount(value)).toBeNull()
    }
  })
})
