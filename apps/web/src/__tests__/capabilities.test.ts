import { describe, expect, it } from 'vitest'
import { formatBytes } from '@/capabilities'

describe('formatBytes', () => {
  it('reads as a person would say it, singular included', () => {
    expect(formatBytes(0)).toBe('0 bytes')
    expect(formatBytes(1)).toBe('1 byte')
    expect(formatBytes(900)).toBe('900 bytes')
    expect(formatBytes(1024)).toBe('1 KB')
    expect(formatBytes(1536)).toBe('1.5 KB')
    expect(formatBytes(512 * 1024)).toBe('512 KB')
    expect(formatBytes(10 * 1024 * 1024)).toBe('10 MB')
    expect(formatBytes(2.5 * 1024 * 1024)).toBe('2.5 MB')
  })

  /** Rounding first is what keeps a count just short of a unit from reading "1024.0 KB" or "25.0 MB". */
  it('rounds before choosing the unit and never shows a trailing ".0"', () => {
    expect(formatBytes(1048575)).toBe('1 MB')
    expect(formatBytes(26214000)).toBe('25 MB')
    expect(formatBytes(1024 * 1024 * 1024 - 1)).toBe('1 GB')
    expect(formatBytes(1075)).toBe('1 KB')
    expect(formatBytes(1100)).toBe('1.1 KB')
  })

  /** What an older server sends is no field at all; a sentence must be able to leave the clause out. */
  it('answers nothing for a size that is missing, negative, fractional or not a number', () => {
    for (const value of [undefined, null, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY, '1024', {}]) {
      expect(formatBytes(value)).toBeNull()
    }
  })
})
