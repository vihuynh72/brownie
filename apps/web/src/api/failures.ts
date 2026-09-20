import { ApiRequestError } from '@/api/client'

/**
 * The sentence a page shows when a request it needed failed, for the
 * failures that mean the same thing wherever they happen. Each of these
 * says what is actually wrong and what, if anything, will help; the
 * generic "try reloading" is true of none of them. Returns null for
 * anything else, so that a page keeps its own words for the failures only
 * it can explain.
 *
 * {@code what} names what could not be loaded or done, in words that fit
 * after "does not have", for example "the trash bin".
 */
export function describeCommonFailure(error: unknown, what: string): string | null {
  if (!(error instanceof ApiRequestError)) {
    // fetch itself failed: no answer came back at all.
    return 'Brownie could not be reached. Check your connection, then try again.'
  }
  if (error.routeMissing) {
    return (
      `The Brownie server that answered is older than this page and does not have ${what} yet. ` +
      'Reloading will not change that: the server needs to be updated and restarted.'
    )
  }
  if (error.status === 401) {
    return 'Your session has ended. Sign in again to carry on.'
  }
  // Both of these come with the server's own explanation, which says how long to wait.
  if (error.status === 429) {
    return error.problem?.detail ?? 'Too many requests in a short time. Wait a minute, then try again.'
  }
  if (error.status === 503) {
    return error.problem?.detail ?? 'Brownie is briefly unavailable. Try again in a minute.'
  }
  // An answer with no explanation of Brownie's own came from something in front of it: it is not running, or
  // cannot be reached from there.
  if (error.status >= 500 && error.problem === undefined) {
    return 'Brownie could not be reached. It may not be running; try again in a minute.'
  }
  return null
}

/**
 * Brownie itself said that what was asked for is not there: a 404 from a
 * route it has, with its own explanation. Only then may a page act on it
 * (drop a row, stop following a run, say a document is gone). A 404 with no
 * explanation came from something in front of Brownie, and a missing route
 * means the server is older than the page; neither says anything about the
 * thing asked for, and acting as if it did would, for a run, invite a second
 * paid start of one that is still going.
 */
export function brownieSaysNotThere(error: unknown): boolean {
  return error instanceof ApiRequestError && error.status === 404 && !error.routeMissing && error.problem !== undefined
}
