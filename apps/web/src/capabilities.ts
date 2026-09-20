import { getCapabilities, type CapabilitiesResponse } from '@/api/client'

let cached: Promise<CapabilitiesResponse> | null = null

/** The deployment's limits, fetched once per page load and shared by every upload control. A failed fetch is retried on the next call. */
export function loadCapabilities(): Promise<CapabilitiesResponse> {
  if (!cached) {
    cached = getCapabilities().catch((error: unknown) => {
      cached = null
      throw error
    })
  }
  return cached
}

/** For tests: forget the cached answer. */
export function resetCapabilitiesCache(): void {
  cached = null
}

const LARGER_UNITS = ['KB', 'MB', 'GB', 'TB'] as const

/**
 * "10 MB", "1.5 KB", "1 byte": the size a person reads, not the byte count the server enforces. The figure is
 * rounded to one decimal place before its unit is chosen, so a count just short of a megabyte reads "1 MB" and
 * never "1024.0 KB". Answers null for anything that is not a whole number of bytes, because an older server may
 * not send the size at all and a sentence must then leave the clause out.
 */
export function formatBytes(bytes: unknown): string | null {
  if (typeof bytes !== 'number' || !Number.isInteger(bytes) || bytes < 0) return null
  if (bytes < 1024) return bytes === 1 ? '1 byte' : `${bytes} bytes`
  let value = bytes / 1024
  let unit = 0
  let rounded = toTenths(value)
  while (rounded >= 1024 && unit < LARGER_UNITS.length - 1) {
    value /= 1024
    unit += 1
    rounded = toTenths(value)
  }
  return `${Number.isInteger(rounded) ? rounded : rounded.toFixed(1)} ${LARGER_UNITS[unit]}`
}

function toTenths(value: number): number {
  return Math.round(value * 10) / 10
}
