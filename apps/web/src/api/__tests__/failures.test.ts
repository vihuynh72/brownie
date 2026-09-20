import { describe, expect, it } from 'vitest'
import { ApiRequestError } from '@/api/client'
import { brownieSaysNotThere, describeCommonFailure } from '@/api/failures'

function problem(status: number, detail: string, code = 'X') {
  return { status, title: 't', code, detail, correlationId: 'c', fields: [], recoveryActions: [] }
}

describe('describeCommonFailure', () => {
  it('says the server is older than the page, and that reloading will not help, when the route is missing', () => {
    const text = describeCommonFailure(new ApiRequestError(404, problem(404, 'No static resource api/v1/data-practices.')), 'the trash bin')

    expect(text).toContain('older than this page')
    expect(text).toContain('does not have the trash bin yet')
    expect(text).toContain('Reloading will not change that')
  })

  it("repeats the server's own explanation when it is busy or away, because that is what says how long to wait", () => {
    expect(describeCommonFailure(new ApiRequestError(429, problem(429, 'Wait 12 seconds and try again.')), 'x')).toBe(
      'Wait 12 seconds and try again.',
    )
    expect(describeCommonFailure(new ApiRequestError(503, problem(503, 'The database is away.')), 'x')).toBe('The database is away.')
    expect(describeCommonFailure(new ApiRequestError(503, undefined), 'x')).toContain('Try again in a minute')
  })

  it('says Brownie could not be reached when nothing of Brownie answered at all', () => {
    expect(describeCommonFailure(new TypeError('Failed to fetch'), 'x')).toContain('could not be reached')
    // What a proxy in front of it answers when it is not running: no explanation of Brownie's own.
    expect(describeCommonFailure(new ApiRequestError(502, undefined), 'x')).toContain('could not be reached')
  })

  it('says a session ended, and leaves every other failure to the page that knows what it means', () => {
    expect(describeCommonFailure(new ApiRequestError(401, undefined), 'x')).toContain('Sign in again')
    expect(describeCommonFailure(new ApiRequestError(404, problem(404, 'Document 12 not found.')), 'x')).toBeNull()
    expect(describeCommonFailure(new ApiRequestError(409, problem(409, 'Conflict.')), 'x')).toBeNull()
    expect(describeCommonFailure(new ApiRequestError(500, problem(500, 'Unexpected.')), 'x')).toBeNull()
  })

  it("takes only Brownie's own explained 404 as saying that a thing is not there", () => {
    expect(brownieSaysNotThere(new ApiRequestError(404, problem(404, 'Document 12 not found.', 'NOT_FOUND')))).toBe(true)
    expect(brownieSaysNotThere(new ApiRequestError(404, undefined))).toBe(false)
    expect(brownieSaysNotThere(new ApiRequestError(404, problem(404, 'No static resource api/v1/x.', 'NOT_FOUND')))).toBe(false)
    expect(brownieSaysNotThere(new ApiRequestError(410, problem(410, 'Gone.')))).toBe(false)
    expect(brownieSaysNotThere(new TypeError('Failed to fetch'))).toBe(false)
  })
})
