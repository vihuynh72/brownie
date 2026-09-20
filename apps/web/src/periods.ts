/**
 * Periods as they read in a sentence. Each takes whatever the server sent,
 * because an older server may not send it at all, and answers null for
 * anything that is not a whole number of at least one: a sentence then
 * leaves the clause out rather than reading "for  days" or "for 0 days".
 */
export function dayCount(value: unknown): string | null {
  if (!isPositiveWholeNumber(value)) return null
  return value === 1 ? 'a day' : `${value} days`
}

/** Hours, or days when they come to a whole number of days. */
export function hourCount(value: unknown): string | null {
  if (!isPositiveWholeNumber(value)) return null
  if (value % 24 === 0) return dayCount(value / 24)
  return value === 1 ? 'an hour' : `${value} hours`
}

function isPositiveWholeNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 1
}
